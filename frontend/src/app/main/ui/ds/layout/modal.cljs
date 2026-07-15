;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.ds.layout.modal
  (:require-macros
   [app.main.style :as stl])
  (:require
   ["@penpot/ui" :as ui]
   [app.common.data :as d]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private schema:modal-header
  [:map
   [:title {:optional true} [:maybe :string]]
   [:class {:optional true} [:maybe :string]]])

(mf/defc modal-header*
  {::mf/schema schema:modal-header}
  [{:keys [title class children] :rest props}]
  (let [props (mf/spread-props props
                               {:class [class (stl/css :modal-header)]})]
    [:> :div props
     (when title
       [:> heading* {:typography t/headline-medium
                     :level 2
                     :class (stl/css :modal-header-title)}
        title])
     children]))

(def ^:private schema:modal-content
  [:map
   [:class {:optional true} [:maybe :string]]])

(mf/defc modal-content*
  {::mf/schema schema:modal-content}
  [{:keys [class children] :rest props}]
  (let [props (mf/spread-props props
                               {:class [class (stl/css :modal-content)]})]
    [:> :div props
     children]))

(def ^:private schema:modal-body
  [:map
   [:class {:optional true} [:maybe :string]]])

(mf/defc modal-body*
  {::mf/schema schema:modal-body}
  [{:keys [class children] :rest props}]
  (let [props (mf/spread-props props
                               {:class [class (stl/css :modal-body)]})]
    [:> :div props
     children]))

(def ^:private schema:modal-footer
  [:map
   [:class {:optional true} [:maybe :string]]])

(mf/defc modal-footer*
  {::mf/schema schema:modal-footer}
  [{:keys [class children] :rest props}]
  (let [props (mf/spread-props props
                               {:class [class (stl/css :modal-footer)]})]
    [:> :div props
     children]))

(def ^:private schema:modal
  [:map
   [:class {:optional true} [:maybe :string]]
   [:is-open {:optional true} [:maybe :boolean]]
   [:on-open-change {:optional true} [:maybe fn?]]
   [:trigger {:optional true} [:maybe :any]]
   [:is-dismissable {:optional true} [:maybe :boolean]]
   [:size {:optional true} [:maybe [:enum "small" "medium" "large"]]]
   [:hide-close {:optional true} [:maybe :boolean]]])

(mf/defc modal*
  {::mf/schema schema:modal}
  [{:keys [class is-open on-open-change trigger is-dismissable size hide-close children] :rest props}]
  (let [hide-close     (d/nilv hide-close false)
        is-dismissable (d/nilv is-dismissable true)
        size           (d/nilv size "medium")
        close          (ui/useModalClose)
        props
        (mf/spread-props props
                         {:class class
                          :is-open is-open
                          :on-open-change on-open-change
                          :trigger trigger
                          :is-dismissable is-dismissable
                          :size size})]
    [:> ui/Modal props
     [:div {:style {:position "relative"}}
      (when-not hide-close
        [:div {:class (stl/css :modal-close)}
         [:> icon-button* {:icon "close"
                           :variant "ghost"
                           :aria-label (tr "labels.close")
                           :on-click #(when close (close))}]])
      children]]))
