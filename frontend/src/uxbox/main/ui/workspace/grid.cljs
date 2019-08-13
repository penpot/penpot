;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.grid
  (:require
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [uxbox.main.constants :as c]))

;; --- Grid (Component)

(defn- horizontal-line
  [width acc value]
  (let [pos value]
    (conj acc (str/format "M %s %s L %s %s" 0 pos width pos))))

(defn- vertical-line
  [height acc value]
  (let [pos value]
    (conj acc (str/format "M %s %s L %s %s" pos 0 pos height))))

(defn- make-grid-path
  [metadata]
  (let [x-ticks (range 0 c/viewport-width (:grid-x-axis metadata 10))
        y-ticks (range 0 c/viewport-height (:grid-y-axis metadata 10))]
    (as-> [] $
      (reduce (partial vertical-line c/viewport-height) $ x-ticks)
      (reduce (partial horizontal-line c/viewport-width) $ y-ticks)
      (str/join " " $))))

(mf/defc grid
  [{:keys [page] :as props}]
  (let [metadata (:metadata page)
        color (:grid-color metadata "#cccccc")
        path (mf/use-memo {:deps #js [metadata]
                           :fn #(make-grid-path metadata)})]
    [:g.grid {:style {:pointer-events "none"}}
     [:path {:d path :stroke color :opacity "0.3"}]]))
