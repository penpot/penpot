;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.components.truncated-text
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.ds.tooltip.tooltip :refer [tooltip*]]
   [rumext.v2 :as mf]))

(defn truncated?
  "True when the text of `node` is wider than the box that shows it, so
  CSS cuts it (for example with an ellipsis)."
  [node]
  (> (.-scrollWidth node) (.-clientWidth node)))

(mf/defc truncated-text*
  "Renders `text` in an `as` element (a `span` by default) styled by
  `class`, which is expected to cut long text. Only while the text is
  cut, hovering or focusing it shows a tooltip with the full text."
  [{:keys [text as class tooltip-class]}]
  (let [as          (or as "span")
        text-ref    (mf/use-ref nil)
        truncated*  (mf/use-state false)
        truncated   (deref truncated*)

        check-truncated
        (mf/use-fn
         (fn []
           (when-let [node (mf/ref-val text-ref)]
             (reset! truncated* (truncated? node)))))

        text-props
        (mf/props {:class class
                   :ref text-ref})]

    ;; Wrapping the text in the tooltip mounts a new node, so the
    ;; observer is set up again whenever `truncated` changes.
    (mf/with-effect [text truncated]
      (check-truncated)
      (when-let [node (mf/ref-val text-ref)]
        (let [observer (js/ResizeObserver. check-truncated)]
          (.observe observer node)
          #(.disconnect observer))))

    (if ^boolean truncated
      [:> tooltip* {:content text
                    :trigger-ref text-ref
                    :class (dm/str (stl/css :tooltip) " " tooltip-class)}
       [:> as text-props text]]
      [:> as text-props text])))
