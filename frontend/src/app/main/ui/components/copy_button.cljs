;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.components.copy-button
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.event :as-alias ev]
   [app.main.data.notifications :as ntf]
   [app.main.store :as st]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.util.clipboard :as clipboard]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.timers :as tm]
   [rumext.v2 :as mf]))

(defn- show-copy-error
  [cause]
  (let [message (clipboard/error-message cause)]
    (when-not message
      (js/console.error "Clipboard error:" cause))
    (st/emit! (ntf/show {:content  (or message (tr "errors.clipboard-copy-failed"))
                         :level    :error
                         :type     :toast
                         :position :fixed
                         :timeout  ntf/default-timeout}))))

(defn copy-text
  "Write `text` to the clipboard and call `on-copied` once the write
   succeeds. A failed write never calls `on-copied`: it shows an error
   toast instead. Returns the Promise of the whole operation."
  [text on-copied]
  ;; The two-arity `.then` keeps an exception thrown by `on-copied` from
  ;; being reported to the user as a clipboard failure.
  (-> (clipboard/to-clipboard text)
      (.then (fn [_] (on-copied)) show-copy-error)))

(mf/defc copy-button*
  [{:keys [data on-copied children class aria-label]}]
  (let [active* (mf/use-state false)
        active? (deref active*)

        class   (dm/str class " "
                        (stl/css-case
                         :copy-button  (not (some? children))
                         :copy-wrapper (some? children)))

        on-click
        (mf/use-fn
         (mf/deps data on-copied)
         (fn [event]
           (when-not (dom/get-boolean-data event "active")
             (copy-text (if (fn? data) (data) data)
                        (fn []
                          (reset! active* true)
                          (tm/schedule 1000 #(reset! active* false))
                          (when (fn? on-copied) (on-copied event)))))))]

    [:button {:class class
              :aria-label (or aria-label (tr "labels.copy"))
              :data-active (dm/str active?)
              :on-click on-click}
     children
     [:span {:class (stl/css :icon-btn)}
      [:> icon* {:icon-id (if active? i/tick i/clipboard)
                 :size "s"
                 :class (stl/css :icon)}]]]))
