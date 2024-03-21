;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases
  (:require
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.releases.common :as rc]
   [app.main.ui.releases.v1-10]
   [app.main.ui.releases.v1-11]
   [app.main.ui.releases.v1-12]
   [app.main.ui.releases.v1-13]
   [app.main.ui.releases.v1-14]
   [app.main.ui.releases.v1-15]
   [app.main.ui.releases.v1-16]
   [app.main.ui.releases.v1-17]
   [app.main.ui.releases.v1-18]
   [app.main.ui.releases.v1-19]
   [app.main.ui.releases.v1-4]
   [app.main.ui.releases.v1-5]
   [app.main.ui.releases.v1-6]
   [app.main.ui.releases.v1-7]
   [app.main.ui.releases.v1-8]
   [app.main.ui.releases.v1-9]
   [app.main.ui.releases.v2-0]
   [app.util.object :as obj]
   [app.util.timers :as tm]
   [rumext.v2 :as mf]))

;;; --- RELEASE NOTES MODAL

(mf/defc release-notes
  {::mf/props :obj}
  [{:keys [version]}]
  (let [slide* (mf/use-state :start)
        slide  (deref slide*)

        klass* (mf/use-state "fadeInDown")
        klass  (deref klass*)

        navigate
        (mf/use-fn #(reset! slide* %))

        next
        (mf/use-fn
         (mf/deps slide)
         (fn []
           (if (= slide :start)
             (navigate 0)
             (navigate (inc slide)))))

        finish
        (mf/use-fn
         (mf/deps version)
         #(st/emit! (modal/hide)
                    (du/mark-onboarding-as-viewed {:version version})))]

    (mf/with-effect []
      #(st/emit! (du/mark-onboarding-as-viewed {:version version})))

    (mf/with-effect [slide]
      (when (not= :start slide)
        (reset! klass* "fadeIn"))
      (let [sem (tm/schedule 300 #(reset! klass* nil))]
        (fn []
          (reset! klass* nil)
          (tm/dispose! sem))))

    (rc/render-release-notes
     {:next next
      :navigate navigate
      :finish finish
      :klass klass
      :slide slide
      :version version})))

(mf/defc release-notes-modal
  {::mf/wrap-props false
   ::mf/register modal/components
   ::mf/register-as :release-notes}
  [props]
  (let [versions (methods rc/render-release-notes)
        version  (obj/get props "version")]
    (when (contains? versions version)
      [:div.relnotes
       [:> release-notes props]])))

(defmethod rc/render-release-notes "0.0"
  [params]
  (rc/render-release-notes (assoc params :version "2.0")))
