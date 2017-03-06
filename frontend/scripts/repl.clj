(require '[cljs.repl :as repl])
(require '[cljs.repl.node :as node])

(def options {:output-dir "out/repl"})

(repl/repl* (node/repl-env) options)
