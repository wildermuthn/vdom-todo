(ns server.todos
  (:require
    [server.rdb :as rdb]
    [server.vdom :as vdom]
    [server.ws :as ws]
    [cognitect.transit :as t]
    [cljs.core.async :refer [chan put! take! >! <!]])
  (:require-macros
    [cljs.core.async.macros :refer [go go-loop]]))

;;; RethinkDB Todo Utils

(defonce todos-atom (atom []))
(defonce current-todos-nodes (atom nil))

(defn sort-todos [coll]
  (sort-by :created < coll))

(defn reset-todos! [coll]
  (reset! todos-atom coll))

(def update-todos! (comp reset-todos! sort-todos))

(defn update-todo [{new-todo :new_val old-todo :old_val}]
  (let [same? #(= (:id %) (:id old-todo))
        update #(if-not (same? %) % new-todo)
        map-update #(->> % (map update) (remove nil?))]
    (-> @todos-atom map-update update-todos!)))

(defn insert-todo [todo]
  (let [add #(conj @todos-atom %)]
    (-> todo add update-todos!)))

(defn handle-todos-update [db-change]
  (let [convert #(js->clj % :keywordize-keys true)
        handle #(if-not (:old_val %)
                 (insert-todo (:new_val %))
                 (update-todo %))]
    (-> db-change convert handle)))

(defn initialize-todos [changes-ch]
  (go-loop []
           (handle-todos-update (<! changes-ch))
           (recur)))

;;; Todo Virtual-Dom

(defn todo-checkbox
  "Render checkbox for todo. On click, trigger :todo.click action."
  [{:keys [id title completed editing] :as data}]
  (let [checked (if completed
                  "checked"
                  js/undefined)
        attrs {:attributes {:class "toggle"
                            :type "checkbox"
                            :key (str id "input-checkbox")
                            :onclick (ws/client-echo [:todo.click
                                                      {:id id :completed completed}])}
               :checked checked}]
    (vdom/input attrs)))

(defn todo-li
  "Render todo as a list item"
  [{:keys [id title completed editing] :as data} & body]
  (let [attrs {:class (cond
                        completed "completed"
                        editing "editing"
                        true "")}]
    (apply vdom/li attrs body)))

(defn todo-label
  "Render todo vdom/label . Clicking on vdom/label  triggers :input.click"
  [{:keys [id title completed editing]}]
  (vdom/label {:onclick
               (ws/client-echo [:input.click {:id id :editing editing}])}
              title))

(defn todo-edit [{:keys [id title completed editing]}]
  (vdom/input {:class "edit"
               :onvdom/input (ws/client-action {:action :input.update :id id})}))

(def todo-view (partial vdom/div {:class "view"}))

(defn todo-delete [id]
  (vdom/button {:class "destroy"
                :onclick (ws/client-echo [:icon.x.click {:id id}])}))

(defn render-todo
  "Render one todo as a :vdom/li  with an :input, :vdom/label , and delete button."
  [todo]
  (todo-li todo
           (todo-view
             (todo-checkbox todo)
             (todo-label todo)
             (todo-delete (:id todo)))
           (todo-edit todo)))

(defn wrap-siblings
  "Rendering utility to wrap a list inside a :div"
  [coll]
  (let [input-node (vdom/render-input "send-on-keypress" (ws/client-action {:action :input.update}))]
    (vdom/vnode. "div" #js {} (to-array coll))))

(defn todos-nodes
  "Renders todos into virtual-dom nodes"
  []
  (->> @todos-atom
       (map render-todo)
       wrap-siblings))

;;; Event Handlers

(defonce message-ch (chan 100))
(defonce conn-ch (chan))


(defn handle-message [conn msg]
  (let [data (t/read ws/reader msg)]
    (put! message-ch data)
    (println "Websocket message: " data)))

(defn handle-x-click [{:keys [id]}]
  (console.log (str "deleting todo: " id))
  (rdb/delete-doc "todos" id))

(defn handle-todo-click [{:keys [id completed]}]
  (console.log (str "update todo completed: " id " " completed))
  (rdb/update-doc "todos" id #js {:completed (not completed)}))

(defn handle-input-click [{:keys [id editing]}]
  (console.log (str "update todo editing: " id " " editing))
  #_(update-doc "todos" id #js {:editing (not editing)}))

(defn handle-todo-update [{:keys [id value]}]
  (console.log (str "update todo " id "  title: " value))
  (rdb/update-doc "todos" id #js {:title value}))

(defonce handle-message-ch
         (go-loop []
                  (let [[action data] (<! message-ch)]
                    (case action
                      :todo.click (handle-todo-click data)
                      :input.update (handle-todo-update data)
                      :input.click (handle-input-click data)
                      :icon.x.click (handle-x-click data)
                      (console.log "Unrecognized action")))
                  (recur)))

;;; Initialize on connection

(defn initialize-todos-and-watch []
  (add-watch
    todos-atom
    :watch-todos-atom
    (fn [_ _ old-todos todos]
      (if (not= old-todos todos)
        (let [old-tree @current-todos-nodes
              new-tree (todos-nodes)]
          (if old-tree
            (ws/patch-todos-dom (vdom/patch-tree old-tree new-tree))
            (ws/insert-todos-dom new-tree))
          (reset! current-todos-nodes new-tree)))))

  (initialize-todos (rdb/get-table-ch "todos")))

(defn handle-ws-conn [conn]
  (println "Websocket connected")
  (swap! ws/conns conj conn)
  (put! conn-ch conn)
  (if @current-todos-nodes
    (ws/initialize-todos-dom conn (todos-nodes))
    (initialize-todos-and-watch))
  (.on conn "message" (partial handle-message conn)))

(defonce on-conn (.on ws/server-conn "connection" #(handle-ws-conn %)))

;;; Load todos into db

(defn next-tick [t f]
  (js/setTimeout f (* t 10)))

(defn reset-sample-todos []
  (rdb/delete-doc "todos" 1)
  (rdb/delete-doc "todos" 2)
  (rdb/delete-doc "todos" 3)
  (next-tick 1 #(rdb/into-table "todos" (clj->js {:id 1
                                                  :title "my first todo"
                                                  :created (rdb/r.now)
                                                  :completed false})))
  (next-tick 2 #(rdb/into-table "todos" (clj->js {:id 2
                                                  :title (str "random todo #" (rand-int 1000))
                                                  :created (rdb/r.now)
                                                  :completed false
                                                  :editing false})))
  (next-tick 3 #(rdb/into-table "todos" (clj->js {:id 3
                                                  :title "my last todo"
                                                  :created (rdb/r.now)
                                                  :completed false}))))