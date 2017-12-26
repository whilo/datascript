(ns datascript.test.store
  (:require
   #?(:cljs [cljs.test    :as t :refer-macros [is are deftest testing]]
      :clj  [clojure.test :as t :refer        [is are deftest testing]])
   [datascript.core :as d]
   [datascript.db :as db]
   [datascript.query-v3 :as q]
   [datascript.test.core :as tdc]
   [hitchhiker.konserve :as kons]
   [hitchhiker.tree.core :as hc :refer [<??]]
   [konserve.filestore :refer [new-fs-store]]))


(def db (d/db-with
         (d/empty-db)
         [{ :db/id 1, :name  "Ivan", :age   15 }
          { :db/id 2, :name  "Petr", :age   37 }
          { :db/id 3, :name  "Ivan", :age   37 }
          { :db/id 4, :age 15 }]))

(def store (kons/add-hitchhiker-tree-handlers
            (<?? (new-fs-store "/tmp/datascript-play"))))


(def backend (kons/->KonserveBackend store))

(defn store-db [db backend]
  (let [{:keys [eavt-durable aevt-durable avet-durable]} db]
    {:eavt-key (kons/get-root-key (:tree (<?? (hc/flush-tree eavt-durable backend))))
     :aevt-key (kons/get-root-key (:tree (<?? (hc/flush-tree aevt-durable backend))))
     :avet-key (kons/get-root-key (:tree (<?? (hc/flush-tree avet-durable backend))))}))

(defn load-db [stored-db]
  (let [{:keys [eavt-key aevt-key avet-key]} stored-db
        empty (d/empty-db)
        eavt-durable (<?? (kons/create-tree-from-root-key store eavt-key))]
    (assoc empty
           :max-eid (datascript.db/init-max-eid (:eavt empty) eavt-durable)
           :eavt-durable eavt-durable
           :aevt-durable (<?? (kons/create-tree-from-root-key store aevt-key))
           :avet-durable (<?? (kons/create-tree-from-root-key store avet-key)))))


(deftest testing-stored-db
  (testing "Testing one round-trip to disk")
  (let [stored-db (store-db db backend)
        loaded-db (load-db stored-db)]
    (is (= (d/q '[:find ?e
                  :where [?e :name]] loaded-db)

           #{[3] [2] [1]}))
    (let [updated (d/db-with loaded-db
                             [{:db/id -1 :name "Hiker" :age 9999}])]
      (is (= (d/q '[:find ?e
                    :where [?e :name "Hiker"]] updated)

             #{[5]})))))


(comment
  (let [new-db
        (time
         (d/db-with
          db
          (for [i (range 5 100000)]
            {:db/id i :name (str "Bot" i) :age i})))]
    (time (d/q '[:find ?e
                 :where [?e :name "Bot42"]] new-db))
    ))


