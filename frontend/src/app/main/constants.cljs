;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.constants)

(def viewport-width 4000)
(def viewport-height 4000)

(def frame-start-x 1200)
(def frame-start-y 1200)

(def grid-x-axis 10)
(def grid-y-axis 10)

(def page-metadata
  "Default data for page metadata."
  {:grid-x-axis grid-x-axis
   :grid-y-axis grid-y-axis
   :grid-color "var(--color-gray-20)"
   :grid-alignment true
   :background "var(--color-white)"})

