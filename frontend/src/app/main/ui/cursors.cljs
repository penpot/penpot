;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.cursors
  (:require-macros [app.main.ui.cursors :refer [cursor-ref cursor-fn]])
  (:require [rumext.alpha :as mf]
            [cuerdas.core :as str]
            [app.util.timers :as ts]))

(def create-artboard (cursor-ref :create-artboard))
(def create-ellipse (cursor-ref :create-ellipse))
(def create-polygon (cursor-ref :create-polygon))
(def create-rectangle (cursor-ref :create-reclangle))
(def create-shape (cursor-ref :create-shape))
(def duplicate (cursor-ref :duplicate 0 0 0))
(def hand (cursor-ref :hand))
(def move-pointer (cursor-ref :move-pointer))
(def pencil (cursor-ref :pencil 0 0 24))
(def pen (cursor-ref :pen 0 0 0))
(def pointer-inner (cursor-ref :pointer-inner 0 0 0))
(def resize-alt (cursor-ref :resize-alt))
(def resize-nesw (cursor-fn :resize-h 45))
(def resize-nwse (cursor-fn :resize-h 135))
(def resize-ew (cursor-fn :resize-h 0))
(def resize-ns (cursor-fn :resize-h 90))
(def rotate (cursor-fn :rotate 90))
(def text (cursor-ref :text))
(def picker (cursor-ref :picker 0 0 24))
(def pointer-node (cursor-ref :pointer-node 0 0 10 32))
(def pointer-move (cursor-ref :pointer-move 0 0 10 42))
(def pen-node (cursor-ref :pen-node 0 0 10 36))

(mf/defc debug-preview
  {::mf/wrap-props false}
  [props]
  (let [rotation (mf/use-state 0)]
    (mf/use-effect (fn [] (ts/interval 100 #(reset! rotation inc))))

    [:section.debug-icons-preview
     (for [[key val] (sort-by first (ns-publics 'app.main.ui.cursors))]
       (when (not= key 'debug-icons-preview)
         (let [value (deref val)
               value (if (fn? value) (value @rotation) value)]
           [:div.cursor-item {:key key}
            [:div {:style {:width "100px"
                           :height "100px"
                           :background-image (-> value (str/replace #"(url\(.*\)).*" "$1"))
                           :background-size "contain"
                           :background-repeat "no-repeat"
                           :background-position "center"
                           :cursor value}}]

            [:span {:style {:white-space "nowrap"
                            :margin-right "1rem"}} (pr-str key)]])))]))

