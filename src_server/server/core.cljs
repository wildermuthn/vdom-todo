(ns server.core
  (:require
    [cljs.nodejs :as nodejs]
    [cljs.pprint :refer [pprint]]
    [cljs.core.async :refer [chan put! take! >! <!] :as ca]
    [cognitect.transit :as t]
    [goog.object :as obj])
  (:require-macros
    [cljs.core.async.macros :refer [go go-loop]]))

;;; Aliases

(def methods? #(cljs.pprint/pprint (js->clj (.methods %) :keywordize-keys true)))

;;; Nodejs Inits

(nodejs/enable-util-print!)
(def -main (fn [] nil))
(set! *main-cli-fn* -main)

;;; DB

(defonce r (nodejs/require "rethinkdb"))
(defonce rdb (atom {}))

(defn cb-log [err res]
  (if-not err
    (.log js/console res)
    (.log js/console err)))

(defn cb-cursor [f]
  (fn [err cursor]
    (if-not err
      (.toArray cursor (fn [err result]
                         (f result))))))

(defn cb-changes [f]
  (fn [err cursor]
    (if-not err
      (.each cursor (fn [err result]
                      (f result))))))

(defn handle-db-conn [err conn]
  (when-not err
    (reset! rdb conn)))

(defn add-table [table]
  (.. r
      (db "test")
      (tableCreate table)
      (run @rdb cb-log)))

(defn into-table [table data]
  (.. r
      (table table)
      (insert data)
      (run @rdb cb-log)))

(defn get-all [table]
  (.. r
      (table table)
      (run @rdb (cb-cursor #(js/console.log %)))))

(defn get-all-changes [table]
  (.. r
      (table table)
      (changes)
      (run @rdb (cb-changes #(js/console.log %)))))

(comment
  (add-table "tv_shows")
  (into-table "tv_shows" (clj->js {:name "airwolf"
                                   :rating 5}))
  (into-table "tv_shows" (clj->js {:name "megaman"
                                   :rating 1}))
  (get-all "tv_shows")
  (get-all-changes "tv_shows"))

(defonce initialize-db
  (.connect r {:host "localhost" :port 28015} handle-db-conn))

;;; Socket communication

(defonce ws (nodejs/require "ws"))
(defonce server (obj/get ws "Server"))
(defonce conn (server. #js {"port" 8070}))
(defonce my-conn (atom {}))
(defonce result (atom {}))

(defn handle-message [conn msg]
  (println "Websocket message: " msg))

(defn handle-ws-conn [conn]
  (println "Websocket connected")
  (reset! my-conn conn)
  (.on conn "message" (partial handle-message conn)))

(defonce on-conn (.on conn "connection" #(handle-ws-conn %)))

;;; VDOM

(defonce vnode (nodejs/require "virtual-dom/vnode/vnode"))
(defonce vtext (nodejs/require "virtual-dom/vnode/vtext"))
(defonce diff (nodejs/require "virtual-dom/diff"))
(defonce create-element (nodejs/require "virtual-dom/create-element"))
(defonce to-json (nodejs/require "vdom-as-json/toJson"))
(defonce from-json (nodejs/require "vdom-as-json/fromJson"))

(defn render [data]
  (vnode. "div" #js {:className "greeting"} (make-array (vtext. (str "hello " data)))))

(def vtree (render "nate"))
(def vtree2 (render "john paul"))

(def el (create-element vtree))
(def diff-vtree (diff vtree vtree2))

(comment
  (.send @my-conn (.stringify js/JSON #js {:type "node"
                                           :data (to-json vtree)}))
  (.send @my-conn (.stringify js/JSON #js {:type "patch"
                                           :data (to-json diff-vtree)}))
  (send-msg (to-json diff-vtree)))








