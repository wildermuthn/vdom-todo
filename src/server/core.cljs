(ns server.core
  (:require
    [cljs.nodejs :as nodejs]
    [cljs.pprint :refer [pprint]]
    [cljs.analyzer :as ana]
    [cljs.core.async :refer [chan put! take! >! <!] :as ca]
    [clojure.walk :as cw]
    [cognitect.transit :as t]
    [goog.object :as obj])
  (:require-macros
    [server.core :refer [defcomp]]
    [server.core :refer [let# anon-fn]]
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
    (println :db-success)
    (println err)))

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
(defn put-change [ch]
  (fn [err cursor]
    (if-not err
      (.each cursor (fn [err result]
                      (put! ch result))))))

(defn handle-db-conn [err conn]
  (when-not err
    (reset! rdb conn)))

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

(defn reset-table [table]
  (drop-table [table])
  (add-table [table]))

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

(defn get-table-sorted [table prop f]
  (.. r
      (table table)
      (orderBy #js {:index (f prop)})
      (run @rdb (cb-cursor #(js/console.log %)))))

(defn get-table-changes [table f]
  (.. r
      (table table)
      (changes #js {:include_initial true})
      (run @rdb (cb-changes f))))

(defn get-table-ch [table]
  (let [ch (chan)]
    (go (.. r
            (table table)
            (changes #js {:include_initial true})
            (run @rdb (put-change ch))))
    ch))

(defn get-table-ordered-ch [table order-fn]
  (let [ch (chan)]
    (go (.. r
            (table table)
            (orderBy #js {:index order-fn})
            (changes #js {:include_initial true})
            (run @rdb (put-change ch))))
    ch))

(defonce todos-atom (atom []))

(defn update-test [data]
  (update-doc "todos" 1 (clj->js data)))

(defn sort-todos [coll]
  (sort-by :created < coll))

(defn reset-todos! [coll]
  (reset! todos-atom coll))

(def update-todos! (comp reset-todos! sort-todos))

(defn update-todo [{new-todo :new_val old-todo :old_val}]
  (let# [same? (= (:id %) (:id old-todo))
         update (if-not (same? %) % new-todo)
         map-update (->> % (map update) (remove nil?))]
    (-> @todos-atom map-update update-todos!)))

(defn insert-todo [todo]
  (let# [add (conj @todos-atom %)]
    (-> todo add update-todos!)))

(defn handle-todos-update [db-change]
  (let# [convert (js->clj % :keywordize-keys true)
         handle (if-not (:old_val %)
                  (insert-todo (:new_val %))
                  (update-todo %))]
    (-> db-change convert handle)))

(defn initialize-todos [changes-ch]
  (go-loop []
    (handle-todos-update (<! changes-ch))
    (recur)))

(defonce initialize-db
  (.connect r {:host "localhost" :port 28015} handle-db-conn))

;;; Socket communication

(defonce ws (nodejs/require "ws"))
(defonce server (obj/get ws "Server"))
(defonce server-conn (server. #js {"port" 8070}))
(defonce conns (atom []))
(defonce result (atom {}))

;;; Message Handlers

(defonce message-ch (chan 100))
(defonce conn-ch (chan))

(defn handle-message [conn msg]
  (let [data (t/read reader msg)]
    (put! message-ch data)
    (println "Websocket message: " data)))

(defn up [{:keys [id completed]}]
  (console.log (str "update todo completed: " id " " completed))
  (update-doc "todos" id #js {:completed (not completed)}))

(defn handle-todo-click [{:keys [id completed]}]
  (console.log (str "update todo completed: " id " " completed))
  (update-doc "todos" id #js {:completed (not completed)}))

(defn handle-input-click [{:keys [id editing]}]
  (console.log (str "update todo editing: " id " " editing))
  (update-doc "todos" id #js {:editing (not editing)}))

(defn handle-todo-update [{:keys [id value]}]
  (console.log (str "update todo " id "  title: " value))
  (update-doc "todos" id #js {:title value}))

(defonce handle-message-ch
  (go-loop []
    (let [[action data] (<! message-ch)]
      (case action
        :todo.click (handle-todo-click data)
        :input.update (handle-todo-update data)
        :input.click (handle-input-click data)
        (console.log "Unrecognized action")))
    (recur)))


;;; VDOM

(defonce vnode (nodejs/require "virtual-dom/vnode/vnode"))
(defonce vtext (nodejs/require "virtual-dom/vnode/vtext"))
(defonce diff (nodejs/require "virtual-dom/diff"))
(defonce to-elem (nodejs/require "virtual-dom/create-element"))
(defonce to-json (nodejs/require "vdom-as-json/toJson"))
(defonce from-json (nodejs/require "vdom-as-json/fromJson"))
(defonce h (js/eval "h = require('virtual-dom/h');"))
(defonce send-dom? (atom true))

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

;;; Vdom Elements

(defn prn-node [node]
  (.toString (to-elem node)))

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
(def span (partial rn :span))

(def wrapper (partial rn :div {:class "wrapper"}))
(def ul (partial rn :ul))
(def li (partial rn :li))
(def input (partial rn :input))
(def label (partial rn :label))
(def button (partial rn :button))
(def ul-wrapper (partial ul {:class :my-wrapper}))

#_ (to-elem (ul-wrapper
              (list
                (li "a")
                (li "b"))))

#_ (= (prn-node (ul-wrapper
                  (li (span "x") (span "y"))
                  (li "b")))
      (prn-node (ul-wrapper
                  (list (li [(span "x") (span "y")])
                        (li "b")))))

#_ (to-elem (rn :div {:class "myclass"} (list "hi")))

(defn render-input [id s]
  (input {:type "text"
          :id id
          :key (str (gensym))
          :oninput s}))

(defn todo-css [completed?]
  (if completed?
    "text-decoration: line-through; -webkit-user-select: none;"
    "text-decoration: none; -webkit-user-select: none;"))

(defn todo-input [{:keys [id title completed editing]
                   :as data}]
  (let [checked (if completed
                      "checked"
                      js/undefined)
        attrs {:attributes {:class "toggle"
                           :type "checkbox"
                           :key (str id "input-checkbox")
                           :onclick (client-echo [:todo.click
                                                  {:id id :completed completed}])}
              :checked checked}]
    (input attrs)))

(defn todo-li [{:keys [id title completed editing]
                    :as data}
               & body]
  (let [attrs {:class (cond
                        completed "completed"
                        editing "editing"
                        true "")}]
    (apply li attrs body)))

(defn todo-label [{:keys [id title completed editing]}]
  (label {:onclick
          (client-echo [:input.click {:id id :editing editing}])}
         title))

(defn todo-edit [{:keys [id title completed editing]}]
  (input {:class "edit"
          :oninput (client-action {:action :input.update :id id})}))

(def todo-view (partial div {:class "view"}))

(def todo-delete (partial button {:class "destroy"}))

(defn render-todo [todo]
  (todo-li todo
      (todo-view
           (todo-input todo)
           (todo-label todo)
           (todo-delete))
      (todo-edit todo)))





































(defn render-button [label s]
  (vnode.  "button"
          #js {:attributes
               #js {:style "font-weight:bold;"
                    :onclick s
                    :key (str (gensym))
                    }}
          #js [(vtext. label)]))

(defn wrap-siblings [coll]
  (let [input-node (render-input "send-on-keypress" (client-action {:action :input.update}))]
    (vnode. "div" #js {} (to-array coll))))

(defn todos-nodes []
    (->> @todos-atom
         (map render-todo)
         wrap-siblings))

(defn render [input & [button]]
  (let [children (clj->js (filter some? [input button]))]
    (vnode. "div" #js {} children)))


(defn patch-tree [a b]
  (diff a b))

(defonce current-todos-nodes (atom nil))

(defn send-msg [conn data]
  (.send conn data))

(defn send-all [data]
  (doseq [conn (.-clients server-conn)]
    (.send conn data)))

(defn initialize-todos-dom [conn tree]
  (console.log "initialize todos")
  (send-msg conn (.stringify js/JSON #js {:type "node" :data (to-json tree)})))

(defn insert-todos-dom [tree]
  (console.log "insert todos")
  (send-all (.stringify js/JSON #js {:type "node" :data (to-json tree)})))

(defn patch-todos-dom [diff]
  (console.log "patch todos")
  (send-all (.stringify js/JSON #js {:type "patch" :data (to-json diff)})))

(defn initialize-todos-and-watch []
  (add-watch
    todos-atom
    :watch-todos-atom
    (fn [_ _ old-todos todos]
      (if (not= old-todos todos)
        (let [old-tree @current-todos-nodes
              new-tree (todos-nodes)]
          (if old-tree
            (patch-todos-dom (patch-tree old-tree new-tree))
            (insert-todos-dom new-tree))
          (reset! current-todos-nodes new-tree)))))

  (initialize-todos (get-table-ch "todos")))

(defn handle-ws-conn [conn]
  (println "Websocket connected")
  (swap! conns conj conn)
  (put! conn-ch conn)
  (if @current-todos-nodes
    (initialize-todos-dom conn (todos-nodes))
    (initialize-todos-and-watch))
  (.on conn "message" (partial handle-message conn)))

(defonce on-conn (.on server-conn "connection" #(handle-ws-conn %)))

(defn next-tick [t f]
  (js/setTimeout f (* t 10)))

(defn reset-sample-todos []
  (delete-doc "todos" 1)
  (delete-doc "todos" 2)
  (delete-doc "todos" 3)
  (next-tick 1 #(into-table "todos" (clj->js {:id 1
                                              :title "my first todo"
                                              :created (r.now)
                                              :completed false})))
  (next-tick 2 #(into-table "todos" (clj->js {:id 2
                                              :title (str "random todo #" (rand-int 1000))
                                              :created (r.now)
                                              :completed false
                                              :editing false})))
  (next-tick 3 #(into-table "todos" (clj->js {:id 3
                                              :title "my last todo"
                                              :created (r.now)
                                              :completed false}))))

(comment
  (add-table "todos")
  (add-index "todos" "created")
  (drop-table "todos")

  (get-table "todos")
  (get-table-sorted "todos" "created" r.asc)
  (get-table-sorted "todos" "created" r.desc)

  (initialize-todos (get-table-ch "todos"))
  (insert-todos-dom (todos-nodes))
  (deref todos-atom)

  (update-test {:title "my first todo!"})
  (delete-doc "todos" 2)

  (reset-sample-todos)

  (update-doc "todos" 1 #js {:completed true}))


(comment

  ;;; Todos
  (initialize-todos-and-watch)

  (deref todos-atom)
  (update-test {:title (str "a random " (rand-int 1000) " todo")})

  (defcomp yo-el2 [:div.myclass {:onclick (client-echo [:yo :dawg2])} "yo2"])
  (send-all (.stringify js/JSON #js {:type "node" :data (to-json yo-el2)}))

  (def input-node (render-input "send-on-keypress" (client-action :input.update)))
  (send-all (.stringify js/JSON #js {:type "node" :data (to-json input-node)}))

  (def alert-btn (render-button "alert" (client-eval #(js/alert "yo, eval me"))))
  (send-all (.stringify js/JSON #js {:type "node" :data (to-json alert-btn)}))

  (def action-btn (render-button "action" (client-action :action-btn.click)))
  (send-all (.stringify js/JSON #js {:type "node" :data (to-json action-btn)}))

  ;;; Elements and JS callbacks
  (def b1 (render-button "action" (client-echo {:action :button.click :data {}})))
  (def b2 (render-button "data" (client-el-value "my-input")))

  (def i1 (render-input "send-on-keypress" (client-value)))
  (def i2 (render-input "my-input" ""))


  (def tree1 (render b1))
  (def tree2 (render b3))

  (def tree4 (render i2 b2))
  )

(comment
  ;;; Initialize DOM
  (.send @conns (.stringify js/JSON #js {:type "node" :data (to-json tree1)}))
  (.send @conns (.stringify js/JSON #js {:type "node" :data (to-json b1)}))

  ;;; Send Patches to DOM
  (.send @conns (.stringify js/JSON #js {:type "patch" :data (to-json (diff tree1 tree2))}))
  (.send @conns (.stringify js/JSON #js {:type "patch" :data (to-json (diff tree2 tree3))}))
  (.send @conns (.stringify js/JSON #js {:type "patch" :data (to-json (diff tree3 tree4))}))
  (.send @conns (.stringify js/JSON #js {:type "patch" :data (to-json (diff tree4 tree1))})))

(comment
  (go-loop
    [conn (<! conn-ch)]
    ;;; On connection, load all elements
    (when @send-dom?
      (.send conn (.stringify js/JSON #js {:type "node" :data (to-json tree1)}))
      (.send conn (.stringify js/JSON #js {:type "node" :data (to-json tree2)}))
      (.send conn (.stringify js/JSON #js {:type "node" :data (to-json tree3)}))
      (.send conn (.stringify js/JSON #js {:type "node" :data (to-json tree4)})))
    (recur (<! conn-ch))))


;;;; Hiccup to VDOM

(def tasks [{:id (rand-int 100)
             :complete true
             :user :one
             :order (rand-int 100)}
            {:id (rand-int 100)
             :complete true
             :user :one
             :order (rand-int 100)}
            {:id (rand-int 100)
             :complete false
             :user :one
             :order (rand-int 100)}
            {:id (rand-int 100)
             :complete false
             :user :one
             :order (rand-int 100)}
            {:id (rand-int 100)
             :complete true
             :user :two
             :order (rand-int 100)}
            {:id (rand-int 100)
             :complete true
             :user :two
             :order (rand-int 100)}
            {:id (rand-int 100)
             :complete false
             :user :two
             :order (rand-int 100)}
            {:id (rand-int 100)
             :complete false
             :user :two
             :order (rand-int 100)}])

(def incomplete (filter (comp not :complete)))
(def group-by-user (partial group-by :user))
(def sort-by-order (partial sort-by :order))

;; (def sort-user-tasks (comp group-by-user sort-by-order incomplete))

(def sort-user-tasks (comp group-by-user sort-by-order (partial sequence incomplete)))

#_ (sort-user-tasks tasks)

(def yo "yo")

(comment

 (list ana/default-namespaces)
 (let [env (ana/empty-env)]
   (pprint env)
   (pprint (ana/resolve-var env server.core/sort-user-tasks))

   true)

  )
