;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.constants)

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
   :grid-color "#cccccc"
   :grid-alignment true
   :background "#ffffff"})

(def zoom-levels
  [0.20 0.21 0.22 0.23 0.24 0.25 0.27 0.28 0.30 0.32 0.34
   0.36 0.38 0.40 0.42 0.44 0.46 0.48 0.51 0.54 0.57 0.60
   0.63 0.66 0.69 0.73 0.77 0.81 0.85 0.90 0.95 1.00 1.05
   1.10 1.15 1.21 1.27 1.33 1.40 1.47 1.54 1.62 1.70 1.78
   1.87 1.96 2.06 2.16 2.27 2.38 2.50 2.62 2.75 2.88 3.00])
