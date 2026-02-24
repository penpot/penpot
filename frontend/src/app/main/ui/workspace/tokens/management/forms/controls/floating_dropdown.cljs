;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.controls.floating-dropdown
  (:require
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(defn use-floating-dropdown [is-open wrapper-ref dropdown-ref]
  (let [position*   (mf/use-state nil)
        position    (deref position*)
        ready*      (mf/use-state false)
        ready       (deref ready*)
        calculate-position
        (fn [node]
          (let [combobox-rect (dom/get-bounding-rect node)
                dropdown-node (mf/ref-val dropdown-ref)
                dropdown-height (if dropdown-node
                                  (->  (dom/get-bounding-rect dropdown-node)
                                       (:height))
                                  0)

                windows-height (-> (dom/get-window-size)
                                   (:height))

                space-below (- windows-height (:bottom combobox-rect))

                open-up? (and dropdown-height
                              (> dropdown-height space-below))

                position (if open-up?
                           {:bottom (str (- windows-height (:top combobox-rect) 12) "px")
                            :left   (str (:left combobox-rect) "px")
                            :width  (str (:width combobox-rect) "px")
                            :placement :top}

                           {:top   (str (+ (:bottom combobox-rect) 4) "px")
                            :left  (str (:left combobox-rect) "px")
                            :width (str (:width combobox-rect) "px")
                            :placement :bottom})]
            (reset! ready* true)
            (reset! position* position)))]

    (mf/with-effect [is-open  dropdown-ref wrapper-ref]
      (when is-open
        (let [handler (fn [event]
                        (let [dropdown-node (mf/ref-val dropdown-ref)
                              target (dom/get-target event)]
                          (when (or (nil? dropdown-node)
                                    (not (instance? js/Node target))
                                    (not (.contains dropdown-node target)))
                            (js/requestAnimationFrame
                             (fn []
                               (let [wrapper-node  (mf/ref-val wrapper-ref)]
                                 (reset! ready* true)
                                 (calculate-position wrapper-node)))))))]
          (handler nil)

          (.addEventListener js/window "resize" handler)
          (.addEventListener js/window "scroll" handler true)

          (fn []
            (.removeEventListener js/window "resize" handler)
            (.removeEventListener js/window "scroll" handler true)))))

    {:style position
     :ready? ready
     :recalculate calculate-position}))