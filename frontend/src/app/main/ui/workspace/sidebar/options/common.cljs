;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.common
  (:require
   [rumext.alpha :as mf]
   [app.util.dom :as dom]))

(mf/defc advanced-options [{:keys [visible? on-close children]}]
  (let [ref (mf/use-ref nil)
        handle-click (fn [event] (when on-close
                                   (do (dom/stop-propagation event)
                                       (on-close))))]
    (mf/use-effect
     (mf/deps visible?)
     (fn []
       (when-let [node (mf/ref-val ref)]
         (when visible?
           (.scrollIntoViewIfNeeded ^js node)))))

    (when visible?
      [:*
       [:div.focus-overlay {:on-click handle-click}]
       [:div.advanced-options-wrapper {:ref ref}
        [:div.advanced-options {}
         children]]])))

