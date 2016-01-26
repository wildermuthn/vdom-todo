(ns server.rdb
  (:require
    [cljs.nodejs :as nodejs]
    [cljs.core.async :refer [chan put! take! >! <!]])
  (:require-macros
    [cljs.core.async.macros :refer [go go-loop]]))

;;; Initialize RethinkDB

(defonce r (nodejs/require "rethinkdb"))
(defonce rdb (atom {}))

(defn handle-db-conn [err conn]
  (when-not err
    (reset! rdb conn)))

(defonce initialize-db
         (.connect r {:host "localhost" :port 28015} handle-db-conn))

;;; RethinkDB Utils

(defn cb-log [err res]
  (if-not err
    (println :db-success)
    (println err)))

(defn cb-cursor [f]
  (fn [err cursor]
    (if-not err
      (.toArray cursor (fn [err result]
                         (f result))))))

(defn put-change [ch]
  (fn [err cursor]
    (if-not err
      (.each cursor (fn [err result]
                      (put! ch result))))))

(defn add-table [table]
  (.. r
      (db "test")
      (tableCreate table)
      (run @rdb cb-log)))

(defn add-index [table prop]
  (.. r
      (db "test")
      (table table)
      (indexCreate prop)
      (run @rdb cb-log)))

(defn drop-table [table]
  (.. r
      (db "test")
      (tableDrop table)
      (run @rdb cb-log)))

(defn into-table [table data]
  (.. r
      (table table)
      (insert data)
      (run @rdb cb-log)))

(defn update-doc [table id data]
  (.. r
      (table table)
      (get id)
      (update data)
      (run @rdb cb-log)))

(defn delete-doc [table id]
  (.. r
      (table table)
      (get id)
      (delete)
      (run @rdb cb-log)))

(defn get-table [table]
  (.. r
      (table table)
      (run @rdb (cb-cursor #(js/console.log %)))))

(defn get-table-ch [table]
  (let [ch (chan)]
    (go (.. r
            (table table)
            (changes #js {:include_initial true})
            (run @rdb (put-change ch))))
    ch))

(defn update-test [data]
  (update-doc "todos" 1 (clj->js data)))
