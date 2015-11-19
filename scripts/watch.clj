(require '[cljs.build.api :as b])

(b/watch "src"
  {:main 'ds-server.core
   :output-to "out/ds_server.js"
   :output-dir "out"})
