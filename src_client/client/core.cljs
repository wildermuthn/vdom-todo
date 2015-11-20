(ns client.core
  (:require [goog.object :as obj]
            [goog.dom :as dom]
            [cognitect.transit :as t]
            [clojure.string]
            [cljs.core.async :as ca]))

;;; Init

(defonce host (clojure.string/replace (.. js/window -document -location -host)
                                      (js/RegExp. ":.*")
                                      ""))
(defonce ws (js/WebSocket. (str "ws://" host ":8070")))
(defonce root-node (atom nil))

;;; Transit

(def reader (t/reader :json))
(def writer (t/writer :json))

(t/write writer :foo)
(t/read reader (t/write writer :foo))

;;; Utils

(defonce d (atom {}))

(defn send-message [msg]
  (let [data (t/read reader msg)]
    (reset! d data)
    (.send ws msg)))

(defn get-value [e]
  (let [text (.-value e)
        msg (t/write writer text)]
    (reset! d text)
    (.send ws msg)))

(defn get-el-value [id]
  (let [el (dom/getElement id)
            text (.-value el)
            msg (t/write writer text)]
        (.send ws msg)))


#_ (send-message (str (rand-int 10000)))

(defn update-element [msg-data]
  (let [data (.fromJson js/vdomAsJson msg-data)
        node (.. js/virtualDom (create data))]
    (.. js/console (log data))
    (.. js/console (log node))
    (.. js/document -body (appendChild node))
    (reset! root-node node)))

(defn patch-element [msg-data]
  (let [data (.fromJson js/vdomAsJson msg-data)]
    (.. js/virtualDom (patch @root-node data))))

(defn handle-message [event]
  (let [msg (.parse js/JSON (obj/get event "data"))
        msg-data (obj/get msg "data")
        msg-type (obj/get msg "type")]
    (case msg-type
      "node" (update-element msg-data)
      (patch-element msg-data))))

(defonce set-handler (obj/set ws "onmessage" handle-message))
