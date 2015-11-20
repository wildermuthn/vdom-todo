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

;;; Transit

(def reader (t/reader :json))
(def writer (t/writer :json))

(t/write writer :foo)
(t/read reader (t/write writer :foo))

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

;;; Message Handlers

(defonce message-ch (chan 100))
(defonce conn-ch (chan))

(defn handle-message [conn msg]
  (let [data (t/read reader msg)]
    (put! message-ch data)
    (println "Websocket message: " data)))

(defn handle-ws-conn [conn]
  (println "Websocket connected")
  (reset! my-conn conn)
  (put! conn-ch conn)
  (.on conn "message" (partial handle-message conn)))

(defonce on-conn (.on conn "connection" #(handle-ws-conn %)))

(defn message-fn [f]
  (take! message-ch f))

(def print-message (partial message-fn println))

;;; VDOM

(defonce vnode (nodejs/require "virtual-dom/vnode/vnode"))
(defonce vtext (nodejs/require "virtual-dom/vnode/vtext"))
(defonce diff (nodejs/require "virtual-dom/diff"))
(defonce create-element (nodejs/require "virtual-dom/create-element"))
(defonce to-json (nodejs/require "vdom-as-json/toJson"))
(defonce from-json (nodejs/require "vdom-as-json/fromJson"))

;;; Client functions

(defn client-echo [data]
  (let [json-data (t/write writer data)
        extra-quotes (.stringify js/JSON json-data)]
    (str "client.core.send_message(" extra-quotes ")")))

(defn client-value []
  (str "client.core.get_value(this)"))

(defn client-el-value [id]
  (str "client.core.get_el_value('" id "')"))

;;; Vdom Elements

(defn render-input [id s]
  (vnode. "input" #js {:attributes
                       #js {:type "text"
                            :id id
                            :key (str (gensym))
                            :oninput s}}))

(defn render-click [label s]
  (vnode.
    "button" #js {:attributes
                  #js {:style "font-weight:bold;"
                       ;; :ev-click #(js/alert "yo delegator")
                       ;; :onmouseenter "client.core.send_message('just got hovered, dude');"
                       ;; :onclick "client.core.send_message('hi there dude');"
                       :onclick s
                       :key (str (gensym))

                       ;; :onclick (str "(" (.toString alert) ")();" )
                       }} #js [(vtext. label)]))

(def b1 (render-click "action" (client-echo {:action :button.click :data {}})))
(def b2 (render-click "action + data" (client-el-value "my-input")))
(def i1 (render-input "send-on-keypress" (client-value)))
(def i2 (render-input "my-input" ""))

(defn render [input & [button]]
  (let [children (clj->js (filter some? [input button]))]
    (vnode. "div" #js {} children)))

(def tree1 (render b1))
(def tree2 (render i1))
(def tree3 (render i2 b2))

(def diff-tree-1-2 (diff tree1 tree2))

(go-loop [conn (<! conn-ch)]
         (.send conn (.stringify js/JSON #js {:type "node" :data (to-json tree1)}))
         (.send conn (.stringify js/JSON #js {:type "node" :data (to-json tree2)}))
         (.send conn (.stringify js/JSON #js {:type "node" :data (to-json tree3)}))
         (recur (<! conn-ch)))

    ;;; Patch
#_ (.send @my-conn (.stringify js/JSON #js {:type "patch"
                                            :data (to-json diff-tree-1-2)}))








