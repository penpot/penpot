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

(def ^:private schema:modal-footer
  [:map
   [:class {:optional true} [:maybe :string]]
   [:start {:optional true} [:maybe :any]]
   [:end {:optional true} [:maybe :any]]
   [:variant {:optional true} [:maybe [:enum "split" "base"]]]])

(mf/defc modal-footer*
  {::mf/schema schema:modal-footer}
  [{:keys [class variant children start end] :rest props}]
  (let [variant (d/nilv variant "base")
        variant-class
        (case variant
          "split"  (stl/css :modal-footer-split)
          "base" (stl/css :modal-footer-base)
          nil)
        props (mf/spread-props props
                               {:class [class (stl/css :modal-footer) variant-class]})]
    (if (= variant "split")
      [:> :div props
       [:> :div {:class (stl/css :modal-footer-left)}
        start]
       [:> :div {:class (stl/css :modal-footer-right)}
        end]]
      [:> :div props
       children])))



(def ^:private schema:modal
  [:map
   [:class {:optional true} [:maybe :string]]
   [:is-open {:optional true} [:maybe :boolean]]
   [:on-open-change {:optional true} [:maybe fn?]]
   [:trigger {:optional true} [:maybe :any]]
   [:is-dismissable {:optional true} [:maybe :boolean]]
   [:size {:optional true} [:maybe [:enum "small" "medium" "large" "xlarge"]]]
   [:hide-close {:optional true} [:maybe :boolean]]
   [:show-body {:optional true} [:maybe :boolean]]])

(mf/defc modal-close-button*
  []
  (let [close (ui/useModalClose)]
    (when close
      [:div {:class (stl/css :modal-close)}
       [:> icon-button* {:icon "close"
                         :variant "ghost"
                         :aria-label (tr "labels.close")
                         :on-click close}]])))

(mf/defc modal*
  {::mf/schema schema:modal}
  [{:keys [class is-open on-open-change trigger is-dismissable size hide-close show-body children] :rest props}]
  (let [hide-close     (d/nilv hide-close false)
        is-dismissable (d/nilv is-dismissable true)
        size           (d/nilv size "medium")
        show-body      (d/nilv show-body true)
        props
        (mf/spread-props props
                         {:class class
                          :is-open is-open
                          :on-open-change on-open-change
                          :trigger trigger
                          :is-dismissable is-dismissable
                          :size size})]
    [:> ui/Modal props
     (when-not hide-close
       [:> modal-close-button*])
     (if show-body
       [:div {:class [class (stl/css :modal-body)]}
        children]
       children)]))
