;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.notifications
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.notifications :as ntf]
   [app.main.store :as st]
   [app.main.ui.ds.notifications.toast :refer [toast*]]
   [app.main.ui.notifications.context-notification :refer [context-notification]]
   [app.main.ui.notifications.inline-notification :refer [inline-notification]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private ref:notification
  (l/derived :notification st/state))

(mf/defc current-notification*
  []
  (let [notification (mf/deref ref:notification)
        on-close     (mf/use-fn #(st/emit! (ntf/hide)))
        actionable?     (and (nil? (:timeout notification))
                             (nil? (:actions notification)))
        inline?      (or (= :inline (:type notification))
                         (= :floating (:position notification)))
        toast?       (or (= :toast (:type notification))
                         (some? (:timeout notification)))
        content      (or (:content notification) "")
        toast-content
        (if-let [links (seq (:links notification))]
          (mf/html
           [:div {:class (stl/css :toast-body)}
            [:div content]
            [:nav {:class (stl/css :toast-links)}
             (for [[index {:keys [label callback]}] (map-indexed vector links)]
               [:a {:key (str "link-" index)
                    :class (stl/css :toast-link)
                    :href "#"
                    :on-click callback}
                label])]])
          content)]

    (when notification
      (cond
        toast?
        [:> toast*
         {:level (or (:level notification) :info)
          :type (:type notification)
          :is-html (boolean (:is-html notification))
          :detail (:detail notification)
          :on-close on-close}
         toast-content]

        inline?
        [:& inline-notification
         {:accept (:accept notification)
          :cancel (:cancel notification)
          :links (:links notification)
          :content (:content notification)}]

        ;; This should be substited with the actionable-notification* component
        actionable?
        [:& context-notification
         {:level (or (:level notification) :info)
          :links (:links notification)
          :content (:content notification)}]

        :else
        [:> toast*
         {:level (or (:level notification) :info)
          :type (:type notification)
          :is-html (boolean (:is-html notification))
          :detail (:detail notification)
          :on-close on-close} toast-content]))))
