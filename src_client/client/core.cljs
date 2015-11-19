(ns client.core
  (:require [goog.object :as obj]
            [cognitect.transit :as t]
            [clojure.string]))

(def host (clojure.string/replace (.. js/window -document -location -host)
                                      (js/RegExp. ":.*")
                                      ""))

(defonce ws (js/WebSocket. (str "ws://" host ":8070")))
(defonce root-node (atom nil))

(defn update-element [msg-data]
  (let [data (.fromJson js/vdomAsJson msg-data)
        node (.. js/virtualDom (create data))]
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
