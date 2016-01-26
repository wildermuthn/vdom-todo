(defproject vdom-todo "0.0.1"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.170"]
                 [org.clojure/core.async "0.2.374"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [com.cognitect/transit-cljs "0.8.225"]
                 [figwheel-sidecar "0.5.0"]]
  :plugins [[lein-cljsbuild "1.1.1"]]
  :clean-targets ^{:protect false} ["resources/public/js/compiled"
                                    "resources/public/js/min"
                                    "target"]
  :cljsbuild {:builds [{:id "dev-server"
                        :source-paths ["src"]
                        :figwheel true
                        :compiler {:main server.core
                                   :output-to "target/server_out/server.js"
                                   :output-dir "target/server_out"
                                   :target :nodejs
                                   :optimizations :none
                                   :source-map true }}]}
  :figwheel {:server-port 3451})
