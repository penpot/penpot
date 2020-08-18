(ns app.util.cli
  "Command line interface helpers.")

(defn exit!
  ([] (exit! 0))
  ([code]
   (System/exit code)))

(defmacro print-err!
  [& args]
  `(binding [*out* *err*]
     (println ~@args)))
