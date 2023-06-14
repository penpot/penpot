;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.cursors
  (:require-macros [app.main.ui.cursors :refer [cursor-ref cursor-fn]])
  (:require
   [app.common.data.macros :as dm]
   [app.util.css :as css]
   [app.util.timers :as ts]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; Static cursors
(def comments (cursor-ref :comments 0 2 20))
(def create-artboard (cursor-ref :create-artboard))
(def create-ellipse (cursor-ref :create-ellipse))
(def create-polygon (cursor-ref :create-polygon))
(def create-rectangle (cursor-ref :create-rectangle))
(def create-shape (cursor-ref :create-shape))
(def duplicate (cursor-ref :duplicate 0 0 0))
(def hand (cursor-ref :hand))
(def move-pointer (cursor-ref :move-pointer))
(def pen (cursor-ref :pen 0 0 0))
(def pen-node (cursor-ref :pen-node 0 0 10 36))
(def pencil (cursor-ref :pencil 0 0 24))
(def picker (cursor-ref :picker 0 0 24))
(def pointer-inner (cursor-ref :pointer-inner 0 0 0))
(def pointer-move (cursor-ref :pointer-move 0 0 10 42))
(def pointer-node (cursor-ref :pointer-node 0 0 10 32))
(def resize-alt (cursor-ref :resize-alt))
(def zoom (cursor-ref :zoom))
(def zoom-in (cursor-ref :zoom-in))
(def zoom-out (cursor-ref :zoom-out))

;; Dynamic cursors
(def resize-ew (cursor-fn :resize-h 0))
(def resize-nesw (cursor-fn :resize-h 45))
(def resize-ns (cursor-fn :resize-h 90))
(def resize-nwse (cursor-fn :resize-h 135))
(def rotate (cursor-fn :rotate 90))
(def text (cursor-fn :text 0))

;; text
(def scale-ew (cursor-fn :scale-h 0))
(def scale-nesw (cursor-fn :scale-h 45))
(def scale-ns (cursor-fn :scale-h 90))
(def scale-nwse (cursor-fn :scale-h 135))

;;
(def resize-ew-2 (cursor-fn :resize-h-2 0))
(def resize-ns-2 (cursor-fn :resize-h-2 90))

(defn get-static
  [name]
  (dm/str "cursor-" name))

(defn get-dynamic
  [name rotation]
  (dm/str "cursor-" name "-" (.floor js/Math rotation)))

(defn init-static-cursor-style
  [style name value]
  (.add style (dm/str ".cursor-" name) (js-obj "cursor" (dm/str value " !important"))))

(defn init-dynamic-cursor-style
  [style name fn]
  (let [rotations (seq (range 0 360 1))]
    (doseq [rotation rotations]
      (.add style (dm/str ".cursor-" name "-" rotation) (js-obj "cursor" (dm/str (fn rotation) " !important"))))))

(defn init-styles
  []
  (let [style (css/create-style)]
    ;; static
    (init-static-cursor-style style "comments" comments)
    (init-static-cursor-style style "create-artboard" create-artboard)
    (init-static-cursor-style style "create-ellipse" create-ellipse)
    (init-static-cursor-style style "create-polygon" create-polygon)
    (init-static-cursor-style style "create-rectangle" create-rectangle)
    (init-static-cursor-style style "create-shape" create-shape)
    (init-static-cursor-style style "duplicate" duplicate)
    (init-static-cursor-style style "hand" hand)
    (init-static-cursor-style style "move-pointer" move-pointer)
    (init-static-cursor-style style "pen" pen)
    (init-static-cursor-style style "pen-node" pen-node)
    (init-static-cursor-style style "pencil" pencil)
    (init-static-cursor-style style "picker" picker)
    (init-static-cursor-style style "pointer-inner" pointer-inner)
    (init-static-cursor-style style "pointer-move" pointer-move)
    (init-static-cursor-style style "pointer-node" pointer-node)
    (init-static-cursor-style style "resize-alt" resize-alt)
    (init-static-cursor-style style "zoom" zoom)
    (init-static-cursor-style style "zoom-in" zoom-in)
    (init-static-cursor-style style "zoom-out" zoom-out)

    ;; dynamic
    (init-dynamic-cursor-style style "resize-ew" resize-ew)
    (init-dynamic-cursor-style style "resize-nesw" resize-nesw)
    (init-dynamic-cursor-style style "resize-ns" resize-ns)
    (init-dynamic-cursor-style style "resize-nwse" resize-nwse)
    (init-dynamic-cursor-style style "rotate" rotate)
    (init-dynamic-cursor-style style "text" text)
    (init-dynamic-cursor-style style "scale-ew" scale-ew)
    (init-dynamic-cursor-style style "scale-nesw" scale-nesw)
    (init-dynamic-cursor-style style "scale-ns" scale-ns)
    (init-dynamic-cursor-style style "scale-nwse" scale-nwse)
    (init-dynamic-cursor-style style "resize-ew-2" resize-ew-2)
    (init-dynamic-cursor-style style "resize-ns-2" resize-ns-2)))
  
(mf/defc debug-preview
  {::mf/wrap-props false}
  []
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
