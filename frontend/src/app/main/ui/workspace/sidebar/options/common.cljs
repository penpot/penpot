;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.common
  (:require
   [rumext.alpha :as mf]))

(mf/defc advanced-options [{:keys [visible? children]}]
  (let [ref (mf/use-ref nil)]
    (mf/use-effect
     (mf/deps visible?)
     (fn []
       (when-let [node (mf/ref-val ref)]
         (when visible?
           (.scrollIntoViewIfNeeded ^js node)))))

    (when visible?
      [:div.advanced-options-wrapper {:ref ref}
       [:div.advanced-options {}
        children]])))

