;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns app.main.ui.dashboard.common
  (:require
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as k]
   [app.util.dom :as dom]
   [app.util.i18n :as t :refer [tr]]))

;; --- Page Title

(mf/defc grid-header
  [{:keys [on-change on-delete value read-only?] :as props}]
  (let [edit? (mf/use-state false)
        input (mf/use-ref nil)]
    (letfn [(save []
              (let [new-value (-> (mf/ref-val input)
                                  (dom/get-inner-text)
                                  (str/trim))]
                (on-change new-value)
                (reset! edit? false)))
            (cancel []
              (reset! edit? false))
            (edit []
              (reset! edit? true))
            (on-input-keydown [e]
              (cond
                (k/esc? e) (cancel)
                (k/enter? e)
                (do
                  (dom/prevent-default e)
                  (dom/stop-propagation e)
                  (save))))]
      [:div.dashboard-title
       [:h2
        (if @edit?
          [:div.dashboard-title-field
           [:span.edit {:content-editable true
                        :ref input
                        :on-key-down on-input-keydown
                        :dangerouslySetInnerHTML {"__html" value}}]
           [:span.close {:on-click cancel} i/close]]
          (if-not read-only?
            [:span.dashboard-title-field {:on-double-click edit} value]
            [:span.dashboard-title-field value]))]
       (when-not read-only?
         [:div.edition
          (if @edit?
            [:span {:on-click save} i/save]
            [:span {:on-click edit} i/pencil])
          [:span {:on-click on-delete} i/trash]])])))

