(ns app.main.ui.workspace.tokens.macros)

(defmacro legacy
  "Purely annotational macro to find instances later to remove when the refactor to tokens-lib is done."
  [& body] `(do ~@body))
