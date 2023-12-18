(ns app.util.gl.macros
  (:refer-clojure :exclude [slurp])
  (:require [clojure.core :as core]))

(defmacro slurp [file]
  (core/slurp file))