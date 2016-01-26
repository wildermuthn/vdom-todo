(ns server.ws
  (:require
    [cljs.nodejs :as nodejs]
    [cljs.core.async :refer [chan put! take! >! <!] :as ca]
    [cognitect.transit :as t]
    [goog.object :as obj])
  (:require-macros
    [cljs.core.async.macros :refer [go go-loop]]))

;;; Socket communication

(defonce ws (nodejs/require "ws"))
(defonce server (obj/get ws "Server"))
(defonce server-conn (server. #js {"port" 8070}))
(defonce conns (atom []))
(defonce to-json (nodejs/require "vdom-as-json/toJson"))

;;; Transit

(def reader (t/reader :json))
(def writer (t/writer :json))

(t/write writer :foo)
(t/read reader (t/write writer :foo))

;;; Vdom
(defonce to-json (nodejs/require "vdom-as-json/toJson"))

;;; Websocket Utils

(defn send-msg [conn data]
  (.send conn data))

(defn send-all [data]
  (doseq [conn (.-clients server-conn)]
    (.send conn data)))

;;; Send DOM

(defn initialize-todos-dom [conn tree]
  (console.log "initialize todos")
  (send-msg conn (.stringify js/JSON #js {:type "node" :data (to-json tree)})))

(defn insert-todos-dom [tree]
  (console.log "insert todos")
  (send-all (.stringify js/JSON #js {:type "node" :data (to-json tree)})))

(defn patch-todos-dom [diff]
  (console.log "patch todos")
  (send-all (.stringify js/JSON #js {:type "patch" :data (to-json diff)})))

;;; Client functions

(defn client-echo [data]
  (let [json-data (t/write writer data)
        extra-quotes (.stringify js/JSON json-data)]
    (str "client.core.send_message(" extra-quotes ")")))

(defn client-value []
  (str "client.core.get_value(this)"))

(defn client-action [data]
  (let [json-data (t/write writer data)
        extra-quotes (.stringify js/JSON json-data)]
    (str "client.core.action_value(" extra-quotes ", this)")))

(defn client-el-value [id]
  (str "client.core.get_el_value('" id "')"))

(defn client-eval [f]
  (str "(" (.toString f) ")();"))