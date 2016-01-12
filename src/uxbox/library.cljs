(ns uxbox.library
  (:require [uxbox.library.colors :as colors]
            [uxbox.library.icons :as icons]
            [uxbox.util.data :refer (index-by-id)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Colors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static +color-collections+
  colors/+collections+)

(def ^:static +color-collections-by-id+
  (index-by-id colors/+collections+))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Icons
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static +icon-collections+
  icons/+collections+)

(def ^:static +icon-collections-by-id+
  (index-by-id icons/+collections+))
