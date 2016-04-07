(require '[cljs.build.api :as b]
         '[cljs.tagged-literals])

(alter-var-root #'cljs.tagged-literals/*cljs-data-readers*
                assoc 'ux/tr (fn [v] `(uxbox.locales/tr ~v)))

(b/watch (b/inputs "src" "vendor" "test")
  {:main 'uxbox.test-runner
   :output-to "out/tests.js"
   :output-dir "out"
   :parallel-build false
   :optimizations :none
   :pretty-print true
   :target :nodejs
   :language-in  :ecmascript5
   :language-out :ecmascript5
   :verbose true})
