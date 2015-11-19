(defproject vdom-todo "0.0.1"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.170"]
                 [org.clojure/core.async "0.2.374"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [com.cognitect/transit-cljs "0.8.225"]]
  :plugins [[lein-cljsbuild "1.1.1"]
            [lein-figwheel "0.5.0-1"]]
  :clean-targets ^{:protect false} ["resources/public/js/compiled"
                                    "resources/public/js/min"
                                    "target"]
  :cljsbuild {:builds [
                       {:id "dev-client"
                        :source-paths ["src_client"]
                        :figwheel true
                        :compiler {:main client.core
                                   :asset-path "js/compiled/out"
                                   :output-to "resources/public/js/compiled/client.js"
                                   :output-dir "resources/public/js/compiled/out"
                                   :elide-asserts false
                                   :source-map-timestamp true
                                   :warnings {:single-segment-namespace false}}}
                       {:id "dev-server"
                        :source-paths ["src_server"]
                        :figwheel true
                        :compiler {:main server.core
                                   :output-to "target/server_out/server.js"
                                   :output-dir "target/server_out"
                                   :target :nodejs
                                   :optimizations :none
                                   :source-map true }}]}
  :figwheel {:server-port 3450})
