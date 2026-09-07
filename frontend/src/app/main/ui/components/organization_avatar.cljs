;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.components.organization-avatar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [rumext.v2 :as mf]))

(mf/defc organization-avatar*
  [{:keys [organization size]}]
  (let [name         (:name organization)
        custom-photo (:custom-photo organization)
        avatar-bg    (:avatar-bg-url organization)
        initials     (d/get-initials name)]

    (if custom-photo
      [:img {:src     custom-photo
             :class   (stl/css-case :organization-avatar true
                                    :organization-avatar-custom true
                                    :organization-avatar-xxxl (= size "xxxl")
                                    :organization-avatar-xxl (= size "xxl")
                                    :organization-avatar-xl (= size "xl"))
             :alt     name}]
      [:div {:class       (stl/css-case :organization-avatar true
                                        :organization-avatar-xxxl (= size "xxxl")
                                        :organization-avatar-xxl (= size "xxl")
                                        :organization-avatar-xl (= size "xl"))
             :aria-hidden "true"}
       [:img {:src   avatar-bg
              :class (stl/css :organization-avatar-bg)
              :alt   ""}]
       (when (seq initials)
         [:span {:class (stl/css-case :organization-avatar-initials true
                                      :size-initials-xxxl (= size "xxxl")
                                      :size-initials-xxl (= size "xxl")
                                      :size-initials-xxl (= size "xl"))} ;; Keep the initials as xxl to make them legible
          initials])])))
