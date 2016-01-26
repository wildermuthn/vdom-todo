(ns server.core
  (:require
    [cljs.nodejs :as nodejs]
    [server.todos :as todos]))

;;; Nodejs Inits

(nodejs/enable-util-print!)
(def -main (fn [] nil))
(set! *main-cli-fn* -main)

;;; Debugging

(comment

  ;; Check if todos loaded into atom
  (deref todos/todos-atom)

  ;; Check if table exists
  (rdb/get-table "todos")

  ;; If exists
  (rdb/drop-table "todos")

  ;; If doesn't exist
  (rdb/add-table "todos")
  (rdb/add-index "todos" "created")

  ;; Reset todos
  (todos/reset-sample-todos)

  ;; Update database and patch dom

  (rdb/update-test {:title "my very first todo!"})
  (rdb/delete-doc "todos" 2)
  (rdb/update-doc "todos" 1 #js {:completed true})
  (rdb/update-doc "todos" 1 #js {:completed false})

  )


