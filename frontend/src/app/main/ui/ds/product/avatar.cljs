;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.product.avatar
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [app.common.types.profile :refer [schema:profile]]
   [app.config :as cfg]
   [app.util.avatars :as avatars]
   [rumext.v2 :as mf]
   [rumext.v2.util :as mfu]))

(def ^:private schema:avatar
  [:map
   [:class {:optional true} :string]
   [:tag {:optional true} :string]
   [:profile schema:profile]
   [:selected {:optional true} :boolean]
   [:variant {:optional true}
    [:maybe [:enum "S" "M" "L"]]]])

(defn- get-url
  [{:keys [photo-url photo-id fullname]}]
  (or photo-url
      (some-> photo-id cfg/resolve-media)
      (avatars/generate {:name fullname})))

(mf/defc avatar*
  {::mf/schema (sm/schema schema:avatar)}
  [{:keys [tag class profile selected variant]}]
  (let [variant (d/nilv variant "S")
        profile (if (object? profile)
                  (mfu/bean profile)
                  profile)
        href    (mf/with-memo [profile]
                  (get-url profile))
        class'  (stl/css-case :avatar true
                              :avatar-small (= variant "S")
                              :avatar-medium (= variant "M")
                              :avatar-large (= variant "L")
                              :is-selected selected)]
    [:> (d/nilv tag "div")
     {:class [class class']
      :title (:fullname profile)}
     [:div {:class (stl/css :avatar-image)}
      [:img {:alt (:fullname profile) :src href}]]]))
