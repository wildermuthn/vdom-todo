(ns server.core
  (:require [clojure.walk :as cw]))

(defn anon-fn [body]
  (let [arg1 (gensym)
        arg2 (gensym)
        body (cw/postwalk-replace {(symbol "%") arg1
                                   (symbol "%1") arg1
                                   (symbol "%2") arg2} body)]
    `(fn [& [~arg1 ~arg2]] ~body)))

(defn anon-pair [pair]
  (let [f-symbol (first pair)
        f-fn (last pair)
        f-anon (anon-fn f-fn)]
    (vector f-symbol f-anon)))

(defmacro let# [bindings body]
  (let [pairs (partition 2 bindings)
        f-bindings (into [] (mapcat anon-pair pairs))]
    `(let ~f-bindings ~body)))


(defmacro defcomp [sym coll]
  `(~'go
       (def ~sym (js/eval (~'<! (~'to-h ~coll))))))


