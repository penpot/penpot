;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.ds.layout.modal
  (:require
   ["@penpot/ui" :as ui]
   [app.common.data :as d]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private schema:modal
  [:map
   [:class {:optional true} [:maybe :string]]
   [:is-open {:optional true} [:maybe :boolean]]
   [:on-open-change {:optional true} [:maybe fn?]]
   [:title {:optional true} [:maybe :string]]
   [:trigger {:optional true} [:maybe :any]]
   [:is-dismissable {:optional true} [:maybe :boolean]]
   [:size {:optional true} [:maybe [:enum "small" "medium" "large"]]]])

(mf/defc modal*
  {::mf/schema schema:modal}
  [{:keys [class is-open on-open-change trigger is-dismissable size children title] :rest props}]
  (let [props
        (mf/spread-props props
                         {:class class
                          :is-open is-open
                          :on-open-change on-open-change

                          :trigger trigger
                          :is-dismissable (d/nilv is-dismissable true)
                          :size (d/nilv size "medium")
                          :heading (mf/html [:> heading* {:typography "title-small" :level 2} title])
                          :close-button (mf/html [:> icon-button* {:icon "close" :variant "ghost"
                                                                   :aria-label (tr "labels.close")}])})]
    [:> ui/Modal props children]))
