(ns server.vdom
  (:require
    [cljs.nodejs :as nodejs]
    [cljs.core.async :refer [chan put! take! >! <!]]
    [server.ws :as ws])
  (:require-macros
    [cljs.core.async.macros :refer [go go-loop]]))

;;; Globals

(defonce vnode (nodejs/require "virtual-dom/vnode/vnode"))
(defonce vtext (nodejs/require "virtual-dom/vnode/vtext"))
(defonce diff (nodejs/require "virtual-dom/diff"))

;;; Utilities

(defn patch-tree [a b]
  (diff a b))

;;; Elements

(defn rn [tag & body]
  (let [[attrs body] (if (map? (first body))
                       [(first body) (rest body)]
                       [{} body])
        attrs (clj->js (if (:attributes attrs)
                         attrs
                         {:attributes attrs}))
        body (if (sequential? (first body))
               (first body)
               body)
        children (map #(if (string? %)
                        (vtext. %)
                        (identity %))
                      body)]
    (vnode. (name tag)
            attrs
            (apply array children))))

(def div (partial rn :div))
(def li (partial rn :li))
(def input (partial rn :input))
(def label (partial rn :label))
(def button (partial rn :button))

(defn render-input [id s]
  (input {:type "text"
          :id id
          :key (str (gensym))
          :oninput s}))
