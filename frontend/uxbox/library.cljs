(ns uxbox.library
  (:require [uxbox.library.colors :as colors]
            [uxbox.util.data :refer (index-by-id)]))

(def ^:static +color-collections+
  colors/+collections+)

(def ^:static +color-collections-by-id+
  (index-by-id colors/+collections+))
