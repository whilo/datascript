(ns datascript.db
  (:require
    #?(:cljs [goog.array :as garray])
     clojure.walk
    [datascript.arrays :as da]
    [hitchhiker.tree.core :as hc :refer [<??]]
    [hitchhiker.tree.messaging :as hmsg]
    [hitchhiker.konserve :as kons]
    [datascript.btset :as btset])
  #?(:cljs (:require-macros [datascript.db :refer [case-tree combine-cmp raise defrecord-updatable cond-let]]))
  (:refer-clojure :exclude [seqable?]))

;; ----------------------------------------------------------------------------

#?(:cljs
   (do
     (def Exception js/Error)
     (def IllegalArgumentException js/Error)
     (def UnsupportedOperationException js/Error)))

(def ^:const tx0 0x20000000)
(def ^:const default-schema nil)

;; ----------------------------------------------------------------------------

#?(:clj
  (defmacro raise [& fragments]
    (let [msgs (butlast fragments)
          data (last fragments)]
      `(throw (ex-info (str ~@(map (fn [m#] (if (string? m#) m# (list 'pr-str m#))) msgs)) ~data)))))

(defn #?@(:clj  [^Boolean seqable?]  
          :cljs [^boolean seqable?])
  [x]
  (and (not (string? x))
  #?(:cljs (or (cljs.core/seqable? x)
               (da/array? x))
     :clj  (or (seq? x)
               (instance? clojure.lang.Seqable x)
               (nil? x)
               (instance? Iterable x)
               (da/array? x)
               (instance? java.util.Map x)))))

(defn- #?@(:clj  [^Boolean neg-number?]
           :cljs [^boolean neg-number?])
  [x]
  (and (number? x) (neg? x)))

;; ----------------------------------------------------------------------------
;; macros and funcs to support writing defrecords and updating
;; (replacing) builtins, i.e., Object/hashCode, IHashEq hasheq, etc.
;; code taken from prismatic:
;;  https://github.com/Prismatic/schema/commit/e31c419c56555c83ef9ee834801e13ef3c112597
;;

(defn- cljs-env?
  "Take the &env from a macro, and tell whether we are expanding into cljs."
  [env]
  (boolean (:ns env)))

#?(:clj
   (defmacro if-cljs
     "Return then if we are generating cljs code and else for Clojure code.
     https://groups.google.com/d/msg/clojurescript/iBY5HaQda4A/w1lAQi9_AwsJ"
     [then else]
     (if (cljs-env? &env) then else)))

(defn combine-hashes [x y]
  #?(:clj  (clojure.lang.Util/hashCombine x y)
     :cljs (hash-combine x y)))

#?(:clj
   (defn- get-sig [method]
     ;; expects something like '(method-symbol [arg arg arg] ...)
     ;; if the thing matches, returns [fully-qualified-symbol arity], otherwise nil
     (and (sequential? method)
          (symbol? (first method))
          (vector? (second method))
          (let [sym (first method)
                ns  (or (some->> sym resolve meta :ns str) "clojure.core")]
            [(symbol ns (name sym)) (-> method second count)]))))

#?(:clj
   (defn- dedupe-interfaces [deftype-form]
     ;; get the interfaces list, remove any duplicates, similar to remove-nil-implements in potemkin
     ;; verified w/ deftype impl in compiler:
     ;; (deftype* tagname classname [fields] :implements [interfaces] :tag tagname methods*)
     (let [[deftype* tagname classname fields implements interfaces & rest] deftype-form]
       (when (or (not= deftype* 'deftype*) (not= implements :implements))
         (throw (IllegalArgumentException. "deftype-form mismatch")))
       (list* deftype* tagname classname fields implements (vec (distinct interfaces)) rest))))

#?(:clj
   (defn- make-record-updatable-clj [name fields & impls]
     (let [impl-map (->> impls (map (juxt get-sig identity)) (filter first) (into {}))
           body     (macroexpand-1 (list* 'defrecord name fields impls))]
       (clojure.walk/postwalk
        (fn [form]
          (if (and (sequential? form) (= 'deftype* (first form)))
            (->> form
                 dedupe-interfaces
                 (remove (fn [method]
                           (when-let [impl (-> method get-sig impl-map)]
                             (not= method impl)))))
            form))
        body))))

#?(:clj
   (defn- make-record-updatable-cljs [name fields & impls]
     `(do
        (defrecord ~name ~fields)
        (extend-type ~name ~@impls))))

#?(:clj
   (defmacro defrecord-updatable [name fields & impls]
     `(if-cljs
       ~(apply make-record-updatable-cljs name fields impls)
       ~(apply make-record-updatable-clj  name fields impls))))

;; ----------------------------------------------------------------------------

;; using defn instead of declare because of http://dev.clojure.org/jira/browse/CLJS-1871
(defn- ^:declared hash-datom [d])
(defn- ^:declared equiv-datom [a b])
(defn- ^:declared seq-datom [d])
(defn- ^:declared nth-datom ([d i]) ([d i nf]))
(defn- ^:declared assoc-datom [d k v])
(defn- ^:declared val-at-datom [d k nf])

(deftype Datom [e a v tx added]
  #?@(:cljs
       [IHash
        (-hash [d] (or (.-__hash d)
                       (set! (.-__hash d) (hash-datom d))))
        IEquiv
        (-equiv [d o] (and (instance? Datom o) (equiv-datom d o)))

        ISeqable
        (-seq [d] (seq-datom d))

        ILookup
        (-lookup [d k] (val-at-datom d k nil))
        (-lookup [d k nf] (val-at-datom d k nf))

        IIndexed
        (-nth [this i] (nth-datom this i))
        (-nth [this i not-found] (nth-datom this i not-found))
        
        IAssociative
        (-assoc [d k v] (assoc-datom d k v))

        IPrintWithWriter
        (-pr-writer [d writer opts]
                    (pr-sequential-writer writer pr-writer
                                          "#datascript/Datom [" " " "]"
                                          opts [(.-e d) (.-a d) (.-v d) (.-tx d) (.-added d)]))
        ]
       :clj
       [Object
        (hashCode [d] (hash-datom d))

        clojure.lang.IHashEq
        (hasheq [d] (hash-datom d))

        clojure.lang.Seqable
        (seq [d] (seq-datom d))

        clojure.lang.IPersistentCollection
        (equiv [d o] (and (instance? Datom o) (equiv-datom d o)))
        (empty [d] (throw (UnsupportedOperationException. "empty is not supported on Datom")))
        (count [d] 5)
        (cons [d [k v]] (assoc-datom d k v))
        
        clojure.lang.Indexed
        (nth [this i]           (nth-datom this i))
        (nth [this i not-found] (nth-datom this i not-found))

        clojure.lang.ILookup
        (valAt [d k] (val-at-datom d k nil))
        (valAt [d k nf] (val-at-datom d k nf))

        clojure.lang.Associative
        (entryAt [d k] (some->> (val-at-datom d k nil) (clojure.lang.MapEntry k)))
        (containsKey [e k] (#{:e :a :v :tx :added} k))
        (assoc [d k v] (assoc-datom d k v))
        ]))

(defn ^Datom datom
  ([e a v]          (Datom. e a v tx0 true))
  ([e a v tx]       (Datom. e a v tx true))
  ([e a v tx added] (Datom. e a v tx added)))
  
(defn datom? [x] (instance? Datom x))

(defn- hash-datom [^Datom d]
  (-> (hash (.-e d))
      (combine-hashes (hash (.-a d)))
      (combine-hashes (hash (.-v d)))))

(defn- equiv-datom [^Datom d ^Datom o]
  (and (= (.-e d) (.-e o))
       (= (.-a d) (.-a o))
       (= (.-v d) (.-v o))))

(defn- seq-datom [^Datom d]
  (list (.-e d) (.-a d) (.-v d) (.-tx d) (.-added d)))

;; keep it fast by duplicating for both keyword and string cases
;; instead of using sets or some other matching func
(defn- val-at-datom [^Datom d k not-found]
  (case k
    :e     (.-e d)        "e"     (.-e d)
    :a     (.-a d)        "a"     (.-a d)
    :v     (.-v d)        "v"     (.-v d)
    :tx    (.-tx d)       "tx"    (.-tx d)
    :added (.-added d)    "added" (.-added d)
    not-found))

(defn- nth-datom
  ([^Datom d ^long i]
    (case i
      0 (.-e d)
      1 (.-a d)
      2 (.-v d)
      3 (.-tx d)
      4 (.-added d)
        #?(:clj  (throw (IndexOutOfBoundsException.))
           :cljs (throw (js/Error. (str "Datom/-nth: Index out of bounds: " i))))))
  ([^Datom d ^long i not-found]
    (case i
      0 (.-e d)
      1 (.-a d)
      2 (.-v d)
      3 (.-tx d)
      4 (.-added d)
        not-found)))

(defn- ^Datom assoc-datom [^Datom d k v]
  (case k
    :e     (Datom. v       (.-a d) (.-v d) (.-tx d) (.-added d))
    :a     (Datom. (.-e d) v       (.-v d) (.-tx d) (.-added d))
    :v     (Datom. (.-e d) (.-a d) v       (.-tx d) (.-added d))
    :tx    (Datom. (.-e d) (.-a d) (.-v d) v        (.-added d))
    :added (Datom. (.-e d) (.-a d) (.-v d) (.-tx d) v)
    (throw (IllegalArgumentException. (str "invalid key for #datascript/Datom: " k)))))

;; printing and reading
;; #datomic/DB {:schema <map>, :datoms <vector of [e a v tx]>}

(defn ^Datom datom-from-reader [vec]
  (apply datom vec))

#?(:clj
   (defmethod print-method Datom [^Datom d, ^java.io.Writer w]
     (.write w (str "#datascript/Datom "))
     (binding [*out* w]
       (pr [(.-e d) (.-a d) (.-v d) (.-tx d) (.-added d)]))))

;; ----------------------------------------------------------------------------
;; datom cmp macros/funcs
;;


#?(:clj
  (defmacro combine-cmp [& comps]
    (loop [comps (reverse comps)
           res   (num 0)]
      (if (not-empty comps)
        (recur
          (next comps)
          `(let [c# ~(first comps)]
             (if (== 0 c#)
               ~res
               c#)))
        res))))

#?(:clj
   (defn- -case-tree [queries variants]
     (if queries
       (let [v1 (take (/ (count variants) 2) variants)
             v2 (drop (/ (count variants) 2) variants)]
         (list 'if (first queries)
               (-case-tree (next queries) v1)
               (-case-tree (next queries) v2)))
       (first variants))))

#?(:clj
   (defmacro case-tree [qs vs]
     (-case-tree qs vs)))

(defn- cmp [o1 o2]
  (if (and o1 o2)
    (compare o1 o2)
    0))

(defn- cmp-num [n1 n2]
  (if (and n1 n2)
    #?(:clj  (Long/compare n1 n2)
       :cljs (- n1 n2))
    0))

(defn cmp-val [o1 o2]
  (if (and (some? o1) (some? o2))
    (compare o1 o2)
    0))

;; Slower cmp-* fns allows for datom fields to be nil.
;; Such datoms come from slice method where they are used as boundary markers.

(defn cmp-datoms-eavt [^Datom d1, ^Datom d2]
  (combine-cmp
    (cmp-num (.-e d1) (.-e d2))
    (cmp (.-a d1) (.-a d2))
    (cmp-val (.-v d1) (.-v d2))
    (cmp-num (.-tx d1) (.-tx d2))))

(defrecord EAVTKey [datom]
  hc/IKeyCompare
  (compare [this other]
    (if (= (type other) EAVTKey)
      (cmp-datoms-eavt datom (.-datom ^EAVTKey other))
      (throw (ex-info "Unexpected key type." {:error :not-eavt-key})))))


(defn cmp-datoms-aevt [^Datom d1, ^Datom d2]
  (combine-cmp
    (cmp (.-a d1) (.-a d2))
    (cmp-num (.-e d1) (.-e d2))
    (cmp-val (.-v d1) (.-v d2))
    (cmp-num (.-tx d1) (.-tx d2))))

(defrecord AEVTKey [datom]
  hc/IKeyCompare
  (compare [this other]
    (cmp-datoms-aevt datom (.-datom ^AEVTKey other))))

(defn cmp-datoms-avet [^Datom d1, ^Datom d2]
  (combine-cmp
    (cmp (.-a d1) (.-a d2))
    (cmp-val (.-v d1) (.-v d2))
    (cmp-num (.-e d1) (.-e d2))
    (cmp-num (.-tx d1) (.-tx d2))))

(defrecord AVETKey [datom]
  hc/IKeyCompare
  (compare [this other]
    (cmp-datoms-avet datom (.-datom ^AVETKey other))))

;; fast versions without nil checks

(defn- cmp-attr-quick [a1 a2]
  ;; either both are keywords or both are strings
  #?(:cljs
     (if (keyword? a1)
       (-compare a1 a2)
       (garray/defaultCompare a1 a2))
     :clj
     (.compareTo ^Comparable a1 a2)))

(defn cmp-datoms-eavt-quick [^Datom d1, ^Datom d2]
  (combine-cmp
    (#?(:clj Long/compare :cljs -) (.-e d1) (.-e d2))
    (cmp-attr-quick (.-a d1) (.-a d2))
    (compare (.-v d1) (.-v d2))
    (#?(:clj Long/compare :cljs -) (.-tx d1) (.-tx d2))))

(defn cmp-datoms-aevt-quick [^Datom d1, ^Datom d2]
  (combine-cmp
    (cmp-attr-quick (.-a d1) (.-a d2))
    (#?(:clj Long/compare :cljs -) (.-e d1) (.-e d2))
    (compare (.-v d1) (.-v d2))
    (#?(:clj Long/compare :cljs -) (.-tx d1) (.-tx d2))))

(defn cmp-datoms-avet-quick [^Datom d1, ^Datom d2]
  (combine-cmp
    (cmp-attr-quick (.-a d1) (.-a d2))
    (compare (.-v d1) (.-v d2))
    (#?(:clj Long/compare :cljs -) (.-e d1) (.-e d2))
    (#?(:clj Long/compare :cljs -) (.-tx d1) (.-tx d2))))

;; ----------------------------------------------------------------------------

;;;;;;;;;; Searching

(defprotocol ISearch
  (-search [data pattern]))

(defprotocol IIndexAccess
  (-datoms [db index components])
  (-seek-datoms [db index components])
  (-index-range [db attr start end]))

(defprotocol IDB
  (-schema [db])
  (-attrs-by [db property]))

;; ----------------------------------------------------------------------------

;; using defn instead of declare because of http://dev.clojure.org/jira/browse/CLJS-1871
(defn- ^:declared hash-db [db])
(defn- ^:declared hash-fdb [db])
(defn- ^:declared equiv-db [a b])
(defn- ^:declared empty-db ([]) ([schema]))
#?(:cljs (defn ^:declared pr-db [db w opts]))
(defn- ^:declared resolve-datom [db e a v t])
(defn- ^:declared validate-attr [attr at])
(defn- ^:declared components->pattern [db index cs])
(defn ^:declared indexing? [db attr])

(require '[clojure.data :refer [diff]])

(defn slice
  ([btset tree datom key create-datom]
   (slice btset tree datom key datom key create-datom))
  ([btset tree datom key datom-to key-to create-datom]
   (let [old (btset/slice btset datom datom-to)
         [a b c d] key
         [d e f g] key-to
         new (->> (hmsg/lookup-fwd-iter tree [a b c d])
                  (filter (fn [[k v]]
                            ;; prefix scan
                            (cond (and d e f g)
                                  (<= (hc/compare k key-to) 0)

                                  (and d e f)
                                  (<= (hc/compare (vec (take 3 k))
                                                  (vec (take 3 key-to))) 0)

                                  (and d e)
                                  (<= (hc/compare (vec (take 2 k))
                                                  (vec (take 2 key-to))) 0)

                                  d
                                  (<= (hc/compare (first k) (first key-to)) 0)

                                  :else true)))
                  (map #(apply create-datom (first %)))
                  seq)]
     (when-not (= (vec old) (vec new))
       (prn "QUERY" key key-to)
       (prn "Mismatch: ")
       (prn "OLD" old)
       (prn "NEW" new)
       (try (prn "DIFF:" (diff old new))
            (catch Error _)))
     new)))


(defrecord-updatable DB [schema eavt eavt-durable aevt aevt-durable avet avet-durable max-eid max-tx rschema hash]
  #?@(:cljs
      [IHash                (-hash  [db]        (hash-db db))
       IEquiv               (-equiv [db other]  (equiv-db db other))
       ISeqable             (-seq   [db]        (-seq  (.-eavt db)))
       IReversible          (-rseq  [db]        (-rseq (.-eavt db)))
       ICounted             (-count [db]        (count (.-eavt db)))
       IEmptyableCollection (-empty [db]        (empty-db (.-schema db)))
       IPrintWithWriter     (-pr-writer [db w opts] (pr-db db w opts))]

      :clj
      [Object               (hashCode [db]      (hash-db db))
       clojure.lang.IHashEq (hasheq [db]        (hash-db db))
       clojure.lang.Seqable (seq [db]           (seq eavt))
       clojure.lang.IPersistentCollection
                            (count [db]         (count eavt))
                            (equiv [db other]   (equiv-db db other))
                            (empty [db]         (empty-db schema))])

  IDB
  (-schema [db] (.-schema db))
  (-attrs-by [db property] ((.-rschema db) property))

  ISearch
  (-search [db pattern]
           (let [[e a v tx] pattern
                 eavt (.-eavt db)
                 aevt (.-aevt db)
                 avet (.-avet db)
                 eavt-durable (.-eavt-durable db)
                 aevt-durable (.-aevt-durable db)
                 avet-durable (.-avet-durable db)
                 create-eavt (fn [e a v tx] (Datom. e a v tx true))
                 create-aevt (fn [a e v tx] (Datom. e a v tx true))
                 create-avet (fn [a v e tx] (Datom. e a v tx true))
                 ]
             (case-tree [e a (some? v) tx]
                        [(slice eavt eavt-durable (Datom. e a v tx nil) [e a v tx] create-eavt)       ;; e a v tx
                         (slice eavt eavt-durable (Datom. e a v nil nil) [e a v] create-eavt)         ;; e a v _
                         (->> (slice eavt eavt-durable (Datom. e a nil nil nil) [e a] create-eavt)    ;; e a _ tx
                              (filter (fn [^Datom d] (= tx (.-tx d)))))
                         (slice eavt eavt-durable (Datom. e a nil nil nil) [e a] create-eavt)         ;; e a _ _
                         (->> (slice eavt eavt-durable (Datom. e nil nil nil nil) [e] create-eavt)    ;; e _ v tx
                              (filter (fn [^Datom d] (and (= v (.-v d))
                                                          (= tx (.-tx d))))))
                         (->> (slice eavt eavt-durable (Datom. e nil nil nil nil) [e] create-eavt)    ;; e _ v _
                              (filter (fn [^Datom d] (= v (.-v d)))))
                         (->> (slice eavt eavt-durable (Datom. e nil nil nil nil) [e] create-eavt)    ;; e _ _ tx
                              (filter (fn [^Datom d] (= tx (.-tx d)))))
                         (slice eavt eavt-durable (Datom. e nil nil nil nil) [e] create-eavt)         ;; e _ _ _
                         (if (indexing? db a)                                             ;; _ a v tx
                           (->> (slice avet avet-durable (Datom. nil a v nil nil) [a v] create-avet)
                                (filter (fn [^Datom d] (= tx (.-tx d)))))
                           (->> (slice aevt aevt-durable (Datom. nil a nil nil nil) [a] create-aevt)
                                (filter (fn [^Datom d] (and (= v (.-v d))
                                                            (= tx (.-tx d)))))))
                         (if (indexing? db a)                                             ;; _ a v _
                           (slice avet avet-durable (Datom. nil a v nil nil) [a v] create-avet)
                           (->> (slice aevt aevt-durable (Datom. nil a nil nil nil) [a] create-aevt)
                                (filter (fn [^Datom d] (= v (.-v d))))))
                         (->> (slice aevt aevt-durable (Datom. nil a nil nil nil) [a] create-aevt)               ;; _ a _ tx
                              (filter (fn [^Datom d] (= tx (.-tx d)))))
                         (slice aevt aevt-durable (Datom. nil a nil nil nil) [a] create-aevt)                    ;; _ a _ _
                         (filter (fn [^Datom d] (and (= v (.-v d))
                                                     (= tx (.-tx d))))
                                 (map #(apply create-eavt (first %)) (hc/lookup-fwd-iter eavt-durable [])) #_eavt)              ;; _ _ v tx
                         (filter (fn [^Datom d] (= v (.-v d)))
                                 (map #(apply create-eavt (first %)) (hc/lookup-fwd-iter eavt-durable [])))                      ;; _ _ v _
                         (filter (fn [^Datom d] (= tx (.-tx d)))
                                 (map #(apply create-eavt (first %)) (hc/lookup-fwd-iter eavt-durable [])))                    ;; _ _ _ tx
                         (map #(apply create-eavt (first %)) (hc/lookup-fwd-iter eavt-durable []))])))                                                         ;; _ _ _ _

  IIndexAccess
  (-datoms [db index cs]
           #_(btset/slice (get db index) (components->pattern db index cs))
           (let [^Datom pat (components->pattern db index cs)
                 ^DB db db
                 [mem dur key create-datom]
                 ({:eavt [(.-eavt db)
                          (.-eavt-durable db)
                          [(.-e pat) (.-a pat) (.-v pat) (.-tx pat)]
                          (fn [e a v t] (Datom. e a v t true))]
                   :aevt [(.-aevt db)
                          (.-aevt-durable db)
                          [(.-a pat) (.-e pat) (.-v pat) (.-tx pat)]
                          (fn [a e v t] (Datom. e a v t true))]
                   :avet [(.-avet db)
                          (.-avet-durable db)
                          [(.-a pat) (.-v pat) (.-e pat) (.-tx pat)]
                          (fn [a v e t] (Datom. e a v t true))]} index)]
             (slice mem dur pat key create-datom)))

  (-seek-datoms [db index cs]
                (let [^Datom pat (components->pattern db index cs)
                      ^DB db db
                      [mem dur key create-datom]
                      ({:eavt [(.-eavt db)
                               (.-eavt-durable db)
                               [(.-e pat) (.-a pat) (.-v pat) (.-tx pat)]
                               (fn [e a v t] (Datom. e a v t true))]
                        :aevt [(.-aevt db)
                               (.-aevt-durable db)
                               [(.-a pat) (.-e pat) (.-v pat) (.-tx pat)]
                               (fn [a e v t] (Datom. e a v t true))]
                        :avet [(.-avet db)
                               (.-avet-durable db)
                               [(.-a pat) (.-v pat) (.-e pat) (.-tx pat)]
                               (fn [a v e t] (Datom. e a v t true))]} index)]
                  (slice mem dur pat key (Datom. nil nil nil nil nil) [nil nil nil nil] create-datom))
    #_(btset/slice (get db index) (components->pattern db index cs) (Datom. nil nil nil nil nil)))

  (-index-range [db attr start end]
    (when-not (indexing? db attr)
      (raise "Attribute" attr "should be marked as :db/index true"))
    (validate-attr attr (list '-index-range 'db attr start end))
    (let [^DB db db
          ^Datom from (resolve-datom db nil attr start nil)
          ^Datom to (resolve-datom db nil attr end nil)]
      (slice (.-avet db)
             (.-avet-durable db)
             from [(.-a from) (.-v from) (.-e from) (.-tx from)]
             to [(.-a to) (.-v to) (.-e to) (.-tx to)]
             (fn [a v e t] (Datom. e a v t true)))
      #_(btset/slice (.-avet db) from to))))

(defn db? [x]
  (and (satisfies? ISearch x)
       (satisfies? IIndexAccess x)
       (satisfies? IDB x)))

;; ----------------------------------------------------------------------------
(defrecord-updatable FilteredDB [unfiltered-db pred hash]
  #?@(:cljs
      [IHash                (-hash  [db]        (hash-fdb db))
       IEquiv               (-equiv [db other]  (equiv-db db other))
       ISeqable             (-seq   [db]        (-datoms db :eavt []))
       ICounted             (-count [db]        (count (-datoms db :eavt [])))
       IPrintWithWriter     (-pr-writer [db w opts] (pr-db db w opts))

       IEmptyableCollection (-empty [_]         (throw (js/Error. "-empty is not supported on FilteredDB")))

       ILookup              (-lookup ([_ _]     (throw (js/Error. "-lookup is not supported on FilteredDB")))
                                     ([_ _ _]   (throw (js/Error. "-lookup is not supported on FilteredDB"))))


       IAssociative         (-contains-key? [_ _] (throw (js/Error. "-contains-key? is not supported on FilteredDB")))
                            (-assoc [_ _ _]       (throw (js/Error. "-assoc is not supported on FilteredDB")))]

      :clj
      [Object               (hashCode [db]      (hash-fdb db))

       clojure.lang.IHashEq (hasheq [db]        (hash-fdb db))

       clojure.lang.IPersistentCollection
                            (count [db]         (count (-datoms db :eavt [])))
                            (equiv [db o]       (equiv-db db o))
                            (cons [db [k v]]    (throw (UnsupportedOperationException. "cons is not supported on FilteredDB")))
                            (empty [db]         (throw (UnsupportedOperationException. "empty is not supported on FilteredDB")))

       clojure.lang.Seqable (seq [db]           (-datoms db :eavt []))

       clojure.lang.ILookup (valAt [db k]       (throw (UnsupportedOperationException. "valAt/2 is not supported on FilteredDB")))
                            (valAt [db k nf]    (throw (UnsupportedOperationException. "valAt/3 is not supported on FilteredDB")))
       clojure.lang.IKeywordLookup (getLookupThunk [db k]
                                                (throw (UnsupportedOperationException. "getLookupThunk is not supported on FilteredDB")))

       clojure.lang.Associative
                            (containsKey [e k]  (throw (UnsupportedOperationException. "containsKey is not supported on FilteredDB")))
                            (entryAt [db k]     (throw (UnsupportedOperationException. "entryAt is not supported on FilteredDB")))
                            (assoc [db k v]     (throw (UnsupportedOperationException. "assoc is not supported on FilteredDB")))])

  IDB
  (-schema [db] (-schema (.-unfiltered-db db)))
  (-attrs-by [db property] (-attrs-by (.-unfiltered-db db) property))

  ISearch
  (-search [db pattern]
           (filter (.-pred db) (-search (.-unfiltered-db db) pattern)))

  IIndexAccess
  (-datoms [db index cs]
           (filter (.-pred db) (-datoms (.-unfiltered-db db) index cs)))

  (-seek-datoms [db index cs]
                (filter (.-pred db) (-seek-datoms (.-unfiltered-db db) index cs)))

  (-index-range [db attr start end]
                (filter (.-pred db) (-index-range (.-unfiltered-db db) attr start end))))

;; ----------------------------------------------------------------------------

(defn attr->properties [k v]
  (case v
    :db.unique/identity  [:db/unique :db.unique/identity :db/index]
    :db.unique/value     [:db/unique :db.unique/value :db/index]
    :db.cardinality/many [:db.cardinality/many]
    :db.type/ref         [:db.type/ref :db/index]
    (when (true? v)
      (case k
        :db/isComponent [:db/isComponent]
        :db/index       [:db/index]
        []))))

(defn- rschema [schema]
  (reduce-kv
    (fn [m attr keys->values]
      (reduce-kv
        (fn [m key value]
          (reduce
            (fn [m prop]
              (assoc m prop (conj (get m prop #{}) attr)))
            m (attr->properties key value)))
        m keys->values))
    {} schema))

(defn- validate-schema-key [a k v expected]
  (when-not (or (nil? v)
                (contains? expected v))
    (throw (ex-info (str "Bad attribute specification for " (pr-str {a {k v}}) ", expected one of " expected)
                    {:error :schema/validation
                     :attribute a
                     :key k
                     :value v}))))

(defn- validate-schema [schema]
  (doseq [[a kv] schema]
    (let [comp? (:db/isComponent kv false)]
      (validate-schema-key a :db/isComponent (:db/isComponent kv) #{true false})
      (when (and comp? (not= (:db/valueType kv) :db.type/ref))
        (throw (ex-info (str "Bad attribute specification for " a ": {:db/isComponent true} should also have {:db/valueType :db.type/ref}")
                        {:error     :schema/validation
                         :attribute a
                         :key       :db/isComponent}))))
    (validate-schema-key a :db/unique (:db/unique kv) #{:db.unique/value :db.unique/identity})
    (validate-schema-key a :db/valueType (:db/valueType kv) #{:db.type/ref})
    (validate-schema-key a :db/cardinality (:db/cardinality kv) #{:db.cardinality/one :db.cardinality/many}))
  schema)




(defn ^DB empty-db
  ([] (empty-db default-schema))
  ([schema]
    {:pre [(or (nil? schema) (map? schema))]}
    (map->DB {
      :schema  (validate-schema schema)
      :eavt    (btset/btset-by cmp-datoms-eavt)
      :eavt-durable (<?? (hc/b-tree (hc/->Config 300 100 200)))
      :aevt    (btset/btset-by cmp-datoms-aevt)
      :aevt-durable (<?? (hc/b-tree (hc/->Config 300 100 200)))
      :avet    (btset/btset-by cmp-datoms-avet)
      :avet-durable (<?? (hc/b-tree (hc/->Config 300 100 200)))
      :max-eid 0
      :max-tx  tx0
      :rschema (rschema schema)
      :hash    (atom 0)})))

;; TODO make private again
(defn init-max-eid [eavt eavt-durable]
  (if-let [slice (vec (slice
                       eavt
                       eavt-durable
                       (Datom. nil nil nil nil nil)
                       [nil nil nil nil]
                       (Datom. (dec tx0) nil nil nil nil)
                       [(dec tx0) nil nil nil]
                       (fn [e a v t] (Datom. e a v t true))))]
    (-> slice rseq first :e) ;; :e of last datom in slice
    0))


(defn ^DB init-db
  ([datoms] (init-db datoms default-schema))
  ([datoms schema]
   (if (empty? datoms)
     (empty-db schema)
     (let [_ (validate-schema schema)
           rschema (rschema schema)
           indexed (:db/index rschema)
           #?@(:cljs
               [ds-arr  (if (array? datoms) datoms (da/into-array datoms))
                eavt    (btset/-btset-from-sorted-arr (.sort ds-arr cmp-datoms-eavt-quick) cmp-datoms-eavt)
                aevt    (btset/-btset-from-sorted-arr (.sort ds-arr cmp-datoms-aevt-quick) cmp-datoms-aevt)
                avet-datoms (-> (reduce (fn [arr d]
                                          (when (contains? indexed (.-a d))
                                            (.push arr d))
                                          arr)
                                        #js [] datoms)
                                (.sort cmp-datoms-avet-quick))
                avet    (btset/-btset-from-sorted-arr avet-datoms cmp-datoms-avet)
                max-eid (init-max-eid eavt)]
               :clj
               [eavt        (apply btset/btset-by cmp-datoms-eavt datoms)
                eavt-durable (<?? (hc/reduce< (fn [t ^Datom datom]
                                                (hmsg/insert t [(.-e datom)
                                                                (.-a datom)
                                                                (.-v datom)
                                                                (.-tx datom)] nil))
                                              (<?? (hc/b-tree (hc/->Config 300 100 200)))
                                              (seq datoms)))
                aevt        (apply btset/btset-by cmp-datoms-aevt datoms)
                aevt-durable (<?? (hc/reduce< (fn [t ^Datom datom]
                                                (hmsg/insert t [(.-a datom)
                                                                (.-e datom)
                                                                (.-v datom)
                                                                (.-tx datom)] nil))
                                              (<?? (hc/b-tree (hc/->Config 300 100 200)))
                                              (seq datoms)))
                avet-datoms (filter (fn [^Datom d] (contains? indexed (.-a d))) datoms)
                avet        (apply btset/btset-by cmp-datoms-avet avet-datoms)
                avet-durable (<?? (hc/reduce< (fn [t ^Datom datom]
                                                (hmsg/insert t [(.-a datom)
                                                                (.-v datom)
                                                                (.-e datom)
                                                                (.-tx datom)] nil))
                                              (<?? (hc/b-tree (hc/->Config 300 100 200)))
                                              (seq datoms)))
                max-eid     (init-max-eid eavt eavt-durable)])
           max-tx (transduce (map (fn [^Datom d] (.-tx d))) max tx0 eavt)]
       (map->DB {:schema  schema
                 :eavt    eavt
                 :eavt-durable eavt-durable
                 :aevt    aevt
                 :aevt-durable aevt-durable
                 :avet    avet
                 :avet-durable avet-durable
                 :max-eid max-eid
                 :max-tx  max-tx
                 :rschema rschema
                 :hash    (atom 0)})))))

(defn- equiv-db-index [x y]
  (loop [xs (seq x)
         ys (seq y)]
    (cond
      (nil? xs) (nil? ys)
      (= (first xs) (first ys)) (recur (next xs) (next ys))
      :else false)))

(defn- hash-db [^DB db]
  (let [h @(.-hash db)]
    (if (zero? h)
      (reset! (.-hash db) (combine-hashes (hash (.-schema db))
                                          (hash (.-eavt db))))
      h)))

(defn- hash-fdb [^FilteredDB db]
  (let [h @(.-hash db)
        datoms (or (-datoms db :eavt []) #{})]
    (if (zero? h)
      (let [datoms (or (-datoms db :eavt []) #{})]
        (reset! (.-hash db) (combine-hashes (hash (-schema db))
                                            (hash-unordered-coll datoms))))
      h)))

(defn- equiv-db [db other]
  (and (or (instance? DB other) (instance? FilteredDB other))
       (= (-schema db) (-schema other))
       (equiv-db-index (-datoms db :eavt []) (-datoms other :eavt []))))

#?(:cljs
   (defn pr-db [db w opts]
     (-write w "#datascript/DB {")
     (-write w ":schema ")
     (pr-writer (-schema db) w opts)
     (-write w ", :datoms ")
     (pr-sequential-writer w
                           (fn [d w opts]
                             (pr-sequential-writer w pr-writer "[" " " "]" opts [(.-e d) (.-a d) (.-v d) (.-tx d)]))
                           "[" " " "]" opts (-datoms db :eavt []))
     (-write w "}")))

#?(:clj
   (do
     (defn pr-db [db, ^java.io.Writer w]
       (.write w (str "#datascript/DB {"))
       (.write w ":schema ")
       (binding [*out* w]
         (pr (-schema db))
         (.write w ", :datoms [")
         (apply pr (map (fn [^Datom d] [(.-e d) (.-a d) (.-v d) (.-tx d)]) (-datoms db :eavt []))))
       (.write w "]}"))

     (defmethod print-method DB [db, ^java.io.Writer w]
       (pr-db db w))

     (defmethod print-method FilteredDB [db, ^java.io.Writer w]
       (pr-db db w))))

(defn db-from-reader [{:keys [schema datoms]}]
  (init-db (map (fn [[e a v tx]] (Datom. e a v tx true)) datoms) schema))

;; ----------------------------------------------------------------------------

;; using defn instead of declare because of http://dev.clojure.org/jira/browse/CLJS-1871
(defn ^:declared entid-strict [db eid])
(defn ^:declared entid-some [db eid])
(defn ^:declared ref? [db attr])

(defn- resolve-datom [db e a v t]
  (when a (validate-attr a (list 'resolve-datom 'db e a v t)))
  (Datom.
    (entid-some db e)               ;; e
    a                               ;; a
    (if (and (some? v) (ref? db a)) ;; v
      (entid-strict db v)
      v)
    (entid-some db t)               ;; t
    nil))

(defn- components->pattern [db index [c0 c1 c2 c3]]
  (case index
    :eavt (resolve-datom db c0 c1 c2 c3)
    :aevt (resolve-datom db c1 c0 c2 c3)
    :avet (resolve-datom db c2 c0 c1 c3)))

;; ----------------------------------------------------------------------------

(defrecord TxReport [db-before db-after tx-data tempids tx-meta])

(defn #?@(:clj  [^Boolean is-attr?]
          :cljs [^boolean is-attr?]) [db attr property]
  (contains? (-attrs-by db property) attr))

(defn #?@(:clj  [^Boolean multival?]
          :cljs [^boolean multival?]) [db attr]
  (is-attr? db attr :db.cardinality/many))

(defn #?@(:clj  [^Boolean ref?]
          :cljs [^boolean ref?]) [db attr]
  (is-attr? db attr :db.type/ref))

(defn #?@(:clj  [^Boolean component?]
          :cljs [^boolean component?]) [db attr]
  (is-attr? db attr :db/isComponent))

(defn #?@(:clj  [^Boolean indexing?]
          :cljs [^boolean indexing?]) [db attr]
  (is-attr? db attr :db/index))

(defn entid [db eid]
  {:pre [(db? db)]}
  (cond
    (number? eid) eid
    (sequential? eid)
      (cond
        (not= (count eid) 2)
          (raise "Lookup ref should contain 2 elements: " eid
                 {:error :lookup-ref/syntax, :entity-id eid})
        (not (is-attr? db (first eid) :db/unique))
          (raise "Lookup ref attribute should be marked as :db/unique: " eid
                 {:error :lookup-ref/unique
                  :entity-id eid})
        (nil? (second eid))
          nil
        :else
          (:e (first (-datoms db :avet eid))))
    #?@(:cljs [(array? eid) (recur db (array-seq eid))])
    :else
      (raise "Expected number or lookup ref for entity id, got " eid
              {:error :entity-id/syntax
               :entity-id eid})))

(defn entid-strict [db eid]
  (or (entid db eid)
      (raise "Nothing found for entity id " eid
             {:error :entity-id/missing
              :entity-id eid})))

(defn entid-some [db eid]
  (when eid
    (entid-strict db eid)))

;;;;;;;;;; Transacting

(defn validate-datom [db ^Datom datom]
  (when (and (.-added datom)
             (is-attr? db (.-a datom) :db/unique))
    (when-let [found (not-empty (-datoms db :avet [(.-a datom) (.-v datom)]))]
      (raise "Cannot add " datom " because of unique constraint: " found
             {:error :transact/unique
              :attribute (.-a datom)
              :datom datom}))))

(defn- validate-eid [eid at]
  (when-not (number? eid)
    (raise "Bad entity id " eid " at " at ", expected number"
           {:error :transact/syntax, :entity-id eid, :context at})))

(defn- validate-attr [attr at]
  (when-not (or (keyword? attr) (string? attr))
    (raise "Bad entity attribute " attr " at " at ", expected keyword or string"
           {:error :transact/syntax, :attribute attr, :context at})))

(defn- validate-val [v at]
  (when (nil? v)
    (raise "Cannot store nil as a value at " at
           {:error :transact/syntax, :value v, :context at})))

(defn- current-tx [report]
  (inc (get-in report [:db-before :max-tx])))

(defn- next-eid [db]
  (inc (:max-eid db)))

(defn- #?@(:clj  [^Boolean tx-id?]
           :cljs [^boolean tx-id?])
  [e]
  (or (= e :db/current-tx)
      (= e ":db/current-tx"))) ;; for datascript.js interop

(defn- advance-max-eid [db eid]
  (cond-> db
    (and (> eid (:max-eid db))
         (< eid tx0)) ;; do not trigger advance if transaction id was referenced
      (assoc :max-eid eid)))

(defn- allocate-eid
  ([report eid]
    (update-in report [:db-after] advance-max-eid eid))
  ([report e eid]
    (cond-> report
      (neg-number? e)
        (assoc-in [:tempids e] eid)
      (tx-id? e)
        (assoc-in [:tempids e] eid)
      true
        (update-in [:db-after] advance-max-eid eid))))

;; In context of `with-datom` we can use faster comparators which
;; do n
(defn- with-datom [db ^Datom datom]
  (validate-datom db datom)
  (let [indexing? (indexing? db (.-a datom))]
    (if (.-added datom)
      (cond-> db
        true      (update-in [:eavt-durable] #(<?? (hmsg/insert % [(.-e datom)
                                                                   (.-a datom)
                                                                   (.-v datom)
                                                                   (.-tx datom)]
                                                                nil)))
        true      (update-in [:eavt] btset/btset-conj datom cmp-datoms-eavt-quick)
        true      (update-in [:aevt] btset/btset-conj datom cmp-datoms-aevt-quick)
        true      (update-in [:aevt-durable] #(<?? (hmsg/insert % [(.-a datom)
                                                                   (.-e datom)
                                                                   (.-v datom)
                                                                   (.-tx datom)]
                                                                nil)))
        indexing? (update-in [:avet] btset/btset-conj datom cmp-datoms-avet-quick)
        indexing? (update-in [:avet-durable] #(<?? (hmsg/insert % [(.-a datom)
                                                                   (.-v datom)
                                                                   (.-e datom)
                                                                   (.-tx datom)]
                                                                nil)))
        true      (advance-max-eid (.-e datom))
        true      (assoc :hash (atom 0)))
      (if-let [removing ^Datom (first (-search db [(.-e datom) (.-a datom) (.-v datom)]))]
        (cond-> db
          true      (update-in [:eavt-durable] #(<?? (hmsg/delete % [(.-e removing) (.-a removing) (.-v removing) (.-tx removing)])))
          true      (update-in [:eavt] btset/btset-disj removing cmp-datoms-eavt-quick)
          true      (update-in [:aevt] btset/btset-disj removing cmp-datoms-aevt-quick)
          true      (update-in [:aevt-durable] #(<?? (hmsg/delete % [(.-a removing) (.-e removing) (.-v removing) (.-tx removing)])))
          indexing? (update-in [:avet] btset/btset-disj removing cmp-datoms-avet-quick)
          indexing? (update-in [:avet-durable] #(<?? (hmsg/delete % [(.-a removing) (.-v removing) (.-e removing) (.-tx removing)])))
          true      (assoc :hash (atom 0)))
        db))))

(comment
  (hc/lookup-fwd-iter (:eavt-durable ) [123 :likes])


  (let [{:keys [eavt eavt-durable]} (with-datom (empty-db) (Datom. 123 :likes "Hans" 0 true))]

    (hc/lookup-fwd-iter eavt-durable [])
    #_(slice eavt eavt-durable (Datom. nil nil nil nil nil) [nil])))



(defn- transact-report [report datom]
  (-> report
      (update-in [:db-after] with-datom datom)
      (update-in [:tx-data] conj datom)))

(defn #?@(:clj  [^Boolean reverse-ref?]
          :cljs [^boolean reverse-ref?]) [attr]
  (cond
    (keyword? attr)
    (= \_ (nth (name attr) 0))
    
    (string? attr)
    (boolean (re-matches #"(?:([^/]+)/)?_([^/]+)" attr))
   
    :else
    (raise "Bad attribute type: " attr ", expected keyword or string"
           {:error :transact/syntax, :attribute attr})))

(defn reverse-ref [attr]
  (cond
    (keyword? attr)
    (if (reverse-ref? attr)
      (keyword (namespace attr) (subs (name attr) 1))
      (keyword (namespace attr) (str "_" (name attr))))

   (string? attr)
   (let [[_ ns name] (re-matches #"(?:([^/]+)/)?([^/]+)" attr)]
     (if (= \_ (nth name 0))
       (if ns (str ns "/" (subs name 1)) (subs name 1))
       (if ns (str ns "/_" name) (str "_" name))))
   
   :else
    (raise "Bad attribute type: " attr ", expected keyword or string"
           {:error :transact/syntax, :attribute attr})))


(defn- check-upsert-conflict [entity acc]
  (let [[e a v] acc
        _e (:db/id entity)]
    (if (or (nil? _e)
            (neg? _e)
            (nil? acc)
            (== _e e))
      acc
      (raise "Conflicting upsert: " [a v] " resolves to " e
             ", but entity already has :db/id " _e
             { :error :transact/upsert
               :entity entity
               :assertion acc }))))


(defn- upsert-eid [db entity]
  (when-let [idents (not-empty (-attrs-by db :db.unique/identity))]
    (->>
      (reduce-kv
        (fn [acc a v] ;; acc = [e a v]
          (if (contains? idents a)
            (if-let [e (:e (first (-datoms db :avet [a v])))]
              (cond
                (nil? acc)        [e a v] ;; first upsert
                (= (get acc 0) e) acc     ;; second+ upsert, but does not conflict
                :else
                (let [[_e _a _v] acc]
                  (raise "Conflicting upserts: " [_a _v] " resolves to " _e
                         ", but " [a v] " resolves to " e
                         { :error     :transact/upsert
                           :entity    entity
                           :assertion [e a v]
                           :conflict  [_e _a _v] })))
              acc) ;; upsert attr, but resolves to nothing
            acc)) ;; non-upsert attr
        nil
        entity)
     (check-upsert-conflict entity)
     first))) ;; getting eid from acc


;; multivals/reverse can be specified as coll or as a single value, trying to guess
(defn- maybe-wrap-multival [db a vs]
  (cond
    ;; not a multival context
    (not (or (reverse-ref? a)
             (multival? db a)))
    [vs]

    ;; not a collection at all, so definitely a single value
    (not (or (da/array? vs)
             (and (coll? vs) (not (map? vs)))))
    [vs]
    
    ;; probably lookup ref
    (and (= (count vs) 2)
         (is-attr? db (first vs) :db.unique/identity))
    [vs]
    
    :else vs))


(defn- explode [db entity]
  (let [eid (:db/id entity)]
    (for [[a vs] entity
          :when  (not= a :db/id)
          :let   [_          (validate-attr a {:db/id eid, a vs})
                  reverse?   (reverse-ref? a)
                  straight-a (if reverse? (reverse-ref a) a)
                  _          (when (and reverse? (not (ref? db straight-a)))
                               (raise "Bad attribute " a ": reverse attribute name requires {:db/valueType :db.type/ref} in schema"
                                      {:error :transact/syntax, :attribute a, :context {:db/id eid, a vs}}))]
          v      (maybe-wrap-multival db a vs)]
      (if (and (ref? db straight-a) (map? v)) ;; another entity specified as nested map
        (assoc v (reverse-ref a) eid)
        (if reverse?
          [:db/add v   straight-a eid]
          [:db/add eid straight-a v])))))

(defn- transact-add [report [_ e a v tx :as ent]]
  (validate-attr a ent)
  (validate-val  v ent)
  (let [tx    (or tx (current-tx report))
        db    (:db-after report)
        e     (entid-strict db e)
        v     (if (ref? db a) (entid-strict db v) v)
        datom (Datom. e a v tx true)]
    (if (multival? db a)
      (if (empty? (-search db [e a v]))
        (transact-report report datom)
        report)
      (if-let [^Datom old-datom (first (-search db [e a]))]
        (if (= (.-v old-datom) v)
          report
          (-> report
            (transact-report (Datom. e a (.-v old-datom) tx false))
            (transact-report datom)))
        (transact-report report datom)))))

(defn- transact-retract-datom [report ^Datom d]
  (let [tx (current-tx report)]
    (transact-report report (Datom. (.-e d) (.-a d) (.-v d) tx false))))

(defn- retract-components [db datoms]
  (into #{} (comp
              (filter (fn [^Datom d] (component? db (.-a d))))
              (map (fn [^Datom d] [:db.fn/retractEntity (.-v d)]))) datoms))

#?(:clj
  (defmacro cond-let [& clauses]
    (when-let [[test expr & rest] clauses]
      `(~(if (vector? test) 'if-let 'if) ~test
         ~expr
         (cond-let ~@rest)))))

#?(:clj
(defmacro some-of
  ([] nil)
  ([x] x)
  ([x & more]
    `(let [x# ~x] (if (nil? x#) (some-of ~@more) x#)))))

;; using defn instead of declare because of http://dev.clojure.org/jira/browse/CLJS-1871
(defn ^:declared transact-tx-data [report es])

(defn retry-with-tempid [report es tempid upserted-eid]
  (if (contains? (:tempids report) tempid)
    (raise "Conflicting upsert: " tempid " resolves"
           " both to " upserted-eid " and " (get (:tempids report) tempid)
      { :error :transact/upsert })
    ;; try to re-run from the beginning
    ;; but remembering that `old-eid` will resolve to `upserted-eid`
    (transact-tx-data (assoc-in report [:tempids tempid] upserted-eid)
                      es)))

(defn transact-tx-data [initial-report initial-es]
  (when-not (or (nil? initial-es)
                (sequential? initial-es))
    (raise "Bad transaction data " initial-es ", expected sequential collection"
           {:error :transact/syntax, :tx-data initial-es}))
  (loop [report initial-report
         es     initial-es]
    (let [[entity & entities] es
          db (:db-after report)]
      (cond
        (empty? es)
          (-> report
              (assoc-in  [:tempids :db/current-tx] (current-tx report))
              (update-in [:db-after :max-tx] inc))

        (nil? entity)
          (recur report entities)

        (map? entity)
          (let [old-eid (:db/id entity)]
            (cond-let
              ;; :db/current-tx => tx
              (tx-id? old-eid)
              (let [id (current-tx report)]
                (recur (allocate-eid report old-eid id)
                       (cons (assoc entity :db/id id) entities)))
             
              ;; lookup-ref => resolved | error
              (sequential? old-eid)
              (let [id (entid-strict db old-eid)]
                (recur report
                       (cons (assoc entity :db/id id) entities)))
             
              ;; upserted => explode | error
              [upserted-eid (upsert-eid db entity)]
              (if (and (neg-number? old-eid)
                       (contains? (:tempids report) old-eid)
                       (not= upserted-eid (get (:tempids report) old-eid)))
                (retry-with-tempid initial-report initial-es old-eid upserted-eid)
                (recur (allocate-eid report old-eid upserted-eid)
                       (concat (explode db (assoc entity :db/id upserted-eid)) entities)))
             
              ;; resolved | allocated-tempid | tempid | nil => explode
              (or (number? old-eid)
                  (nil?    old-eid))
              (let [new-eid (cond
                              (nil? old-eid) (next-eid db)
                              (neg? old-eid) (or (get (:tempids report) old-eid)
                                                 (next-eid db))
                              :else          old-eid)
                    new-entity (assoc entity :db/id new-eid)]                
                (recur (allocate-eid report old-eid new-eid)
                       (concat (explode db new-entity) entities)))
             
              ;; trash => error
              :else
              (raise "Expected number or lookup ref for :db/id, got " old-eid
                { :error :entity-id/syntax, :entity entity })))

        (sequential? entity)
          (let [[op e a v] entity]
            (cond
              (= op :db.fn/call)
                (let [[_ f & args] entity]
                  (recur report (concat (apply f db args) entities)))

              (= op :db.fn/cas)
                (let [[_ e a ov nv] entity
                      e (entid-strict db e)
                      _ (validate-attr a entity)
                      ov (if (ref? db a) (entid-strict db ov) ov)
                      nv (if (ref? db a) (entid-strict db nv) nv)
                      _ (validate-val nv entity)
                      datoms (-search db [e a])]
                  (if (multival? db a)
                    (if (some (fn [^Datom d] (= (.-v d) ov)) datoms)
                      (recur (transact-add report [:db/add e a nv]) entities)
                      (raise ":db.fn/cas failed on datom [" e " " a " " (map :v datoms) "], expected " ov
                             {:error :transact/cas, :old datoms, :expected ov, :new nv}))
                    (let [v (:v (first datoms))]
                      (if (= v ov)
                        (recur (transact-add report [:db/add e a nv]) entities)
                        (raise ":db.fn/cas failed on datom [" e " " a " " v "], expected " ov
                               {:error :transact/cas, :old (first datoms), :expected ov, :new nv })))))

              (tx-id? e)
                (recur report (cons [op (current-tx report) a v] entities))

              (and (ref? db a) (tx-id? v))
                (recur report (cons [op e a (current-tx report)] entities))

              (neg-number? e)
                (if (not= op :db/add)
                  (raise "Negative entity ids are resolved for :db/add only"
                         { :error :transact/syntax
                           :op    entity })
                  (let [upserted-eid  (when (is-attr? db a :db.unique/identity)
                                        (:e (first (-datoms db :avet [a v]))))
                        allocated-eid (get-in report [:tempids e])]
                    (if (and upserted-eid allocated-eid (not= upserted-eid allocated-eid))
                      (retry-with-tempid initial-report initial-es e upserted-eid)
                      (let [eid (or upserted-eid allocated-eid (next-eid db))]
                        (recur (allocate-eid report e eid) (cons [op eid a v] entities))))))

              (and (ref? db a) (neg-number? v))
                (if-let [vid (get-in report [:tempids v])]
                  (recur report (cons [op e a vid] entities))
                  (recur (allocate-eid report v (next-eid db)) es))

              (= op :db/add)
                (recur (transact-add report entity) entities)

              (= op :db/retract)
                (if-let [e (entid db e)]
                  (let [v (if (ref? db a) (entid-strict db v) v)]
                    (validate-attr a entity)
                    (validate-val v entity)
                    (if-let [old-datom (first (-search db [e a v]))]
                      (recur (transact-retract-datom report old-datom) entities)
                      (recur report entities)))
                  (recur report entities))

              (= op :db.fn/retractAttribute)
                (if-let [e (entid db e)]
                  (let [_ (validate-attr a entity)
                        datoms (-search db [e a])]
                    (recur (reduce transact-retract-datom report datoms)
                           (concat (retract-components db datoms) entities)))
                  (recur report entities))

              (= op :db.fn/retractEntity)
                (if-let [e (entid db e)]
                  (let [e-datoms (-search db [e])
                        v-datoms (mapcat (fn [a] (-search db [nil a e])) (-attrs-by db :db.type/ref))]
                    (recur (reduce transact-retract-datom report (concat e-datoms v-datoms))
                           (concat (retract-components db e-datoms) entities)))
                  (recur report entities))

             :else
               (raise "Unknown operation at " entity ", expected :db/add, :db/retract, :db.fn/call, :db.fn/retractAttribute or :db.fn/retractEntity"
                      {:error :transact/syntax, :operation op, :tx-data entity})))
       
       (datom? entity)
         (let [[e a v tx added] entity]
           (if added
             (recur (transact-add report [:db/add e a v tx]) entities)
             (recur report (cons [:db/retract e a v] entities))))

       :else
         (raise "Bad entity type at " entity ", expected map or vector"
                {:error :transact/syntax, :tx-data entity})
       ))))



(comment
  ;; will be needed for flushing
  (require '[konserve.filestore :refer [new-fs-store]])


  (def store (kons/add-hitchhiker-tree-handlers (<?? (new-fs-store "/tmp/datascript"))))

  (def backend (kons/->KonserveBackend store)))
