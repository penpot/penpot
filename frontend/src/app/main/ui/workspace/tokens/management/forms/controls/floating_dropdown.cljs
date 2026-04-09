;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.controls.floating-dropdown
  (:require
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(defn use-floating-dropdown [is-open input-wrapper-ref outer-wrapper-ref dropdown-ref]
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
                           {:bottom (str (- windows-height (:top combobox-rect) -8) "px")
                            :left   (str (:left combobox-rect) "px")
                            :width  (str (:width combobox-rect) "px")
                            :placement :top}

                           {:top   (str (+ (:bottom combobox-rect) 4) "px")
                            :left  (str (:left combobox-rect) "px")
                            :width (str (:width combobox-rect) "px")
                            :placement :bottom})]
            (reset! ready* true)
            (reset! position* position)))]

    (mf/with-effect [is-open dropdown-ref input-wrapper-ref outer-wrapper-ref]
      (when is-open
        (let [recalculate
              (fn []
                (js/requestAnimationFrame
                 (fn []
                   (let [input-node (mf/ref-val input-wrapper-ref)]
                     (calculate-position input-node)))))

              handler
              (fn [event]
                (let [dropdown-node (mf/ref-val dropdown-ref)
                      target (dom/get-target event)]
                  (when (or (nil? dropdown-node)
                            (not (instance? js/Node target))
                            (not (.contains dropdown-node target)))
                    (recalculate))))

              resize-observer (js/ResizeObserver. (fn [_] (recalculate)))
              outer-node      (mf/ref-val outer-wrapper-ref)
              dropdown-node   (mf/ref-val dropdown-ref)]

          (handler nil)

          (.addEventListener js/window "resize" handler)
          (.addEventListener js/window "scroll" handler true)
          (when outer-node
            (.observe resize-observer outer-node))
          (when dropdown-node
            (.observe resize-observer dropdown-node))

          (fn []
            (.removeEventListener js/window "resize" handler)
            (.removeEventListener js/window "scroll" handler true)
            (.disconnect resize-observer)))))

    {:style position
     :ready? ready
     :recalculate calculate-position}))