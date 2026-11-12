;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.subscription-dashboard-test
  "The rules behind the dashboard subscription affordances: which plan a
  subscription reads as, and when the dashboard shows the upgrade banner or
  the members CTA instead of the plan selector.

  The fixtures copy the shape the browser sees: the profile carries the
  seats as `:quantity` and the editors under
  `[:props :subscription :editors]`, where `get-subscription-usage` leaves
  them, while the team carries `:seats`."
  (:require
   [app.main.ui.dashboard.subscription :as subscription]
   [cljs.test :as t :include-macros true]))

(defn- profile
  ([subscription]
   {:props {:subscription subscription}})
  ([subscription editors]
   {:props {:subscription (assoc subscription :editors (vec (range editors)))}}))

(t/deftest a-professional-subscription-reads-as-professional
  (t/is (= "professional"
           (subscription/get-subscription-type
            {:type "professional" :status "active"}))))

(t/deftest a-trialing-unlimited-subscription-reads-as-unlimited
  (t/is (= "unlimited"
           (subscription/get-subscription-type
            {:type "unlimited" :status "trialing"}))))

(t/deftest an-active-enterprise-subscription-reads-as-enterprise
  (t/is (= "enterprise"
           (subscription/get-subscription-type
            {:type "enterprise" :status "active"}))))

(t/deftest a-past-due-subscription-keeps-its-type
  ;; Only `unpaid` and `canceled` demote a plan.
  (t/is (= "unlimited"
           (subscription/get-subscription-type
            {:type "unlimited" :status "past_due"}))))

(t/deftest an-unpaid-subscription-reads-as-professional
  (t/is (= "professional"
           (subscription/get-subscription-type
            {:type "unlimited" :status "unpaid"}))))

(t/deftest a-canceled-subscription-reads-as-professional
  (t/is (= "professional"
           (subscription/get-subscription-type
            {:type "enterprise" :status "canceled"}))))

(t/deftest a-subscription-without-a-type-reads-as-professional
  (t/is (= "professional"
           (subscription/get-subscription-type {:status "active"}))))

(t/deftest a-nil-subscription-reads-as-professional
  (t/is (= "professional" (subscription/get-subscription-type nil))))

(t/deftest a-professional-plan-shows-the-banner-above-eight-editors
  ;; Nine editors do not fit the eight the plan allows.
  (t/is (true? (subscription/show-subscription-dashboard-banner?
                (profile {:type "professional" :status "active"} 9)))))

(t/deftest a-professional-plan-at-eight-editors-does-not-show-the-banner
  (t/is (false? (subscription/show-subscription-dashboard-banner?
                 (profile {:type "professional" :status "active"} 8)))))

(t/deftest a-professional-plan-without-usage-data-does-not-show-the-banner
  ;; The editors arrive with the usage call; until then the count is zero.
  (t/is (false? (subscription/show-subscription-dashboard-banner?
                 (profile {:type "professional" :status "active"})))))

(t/deftest an-unlimited-plan-with-four-editors-over-the-seats-shows-the-banner
  (t/is (true? (subscription/show-subscription-dashboard-banner?
                (profile {:type "unlimited" :status "active" :quantity 20} 24)))))

(t/deftest an-unlimited-plan-with-three-editors-over-the-seats-does-not-show-the-banner
  (t/is (false? (subscription/show-subscription-dashboard-banner?
                 (profile {:type "unlimited" :status "active" :quantity 20} 23)))))

(t/deftest an-unlimited-plan-at-the-seat-limit-does-not-show-the-banner
  (t/is (false? (subscription/show-subscription-dashboard-banner?
                 (profile {:type "unlimited" :status "active" :quantity 25} 30)))))

(t/deftest an-unlimited-plan-with-25-editors-over-24-seats-shows-the-banner
  ;; The editors already passed the size the plan prices per seat.
  (t/is (true? (subscription/show-subscription-dashboard-banner?
                (profile {:type "unlimited" :status "active" :quantity 24} 25)))))

(t/deftest an-unlimited-plan-with-editors-within-the-seats-does-not-show-the-banner
  (t/is (false? (subscription/show-subscription-dashboard-banner?
                 (profile {:type "unlimited" :status "active" :quantity 24} 24)))))

(t/deftest a-canceled-unlimited-plan-shows-the-banner-as-a-professional-plan
  (t/is (true? (subscription/show-subscription-dashboard-banner?
                (profile {:type "unlimited" :status "canceled" :quantity 2} 40)))
        "canceled reads as professional, so the professional rule applies")
  (t/is (false? (subscription/show-subscription-dashboard-banner?
                 (profile {:type "unlimited" :status "canceled" :quantity 2} 8)))))

(t/deftest an-unpaid-unlimited-plan-with-two-editors-does-not-show-the-banner
  (t/is (false? (subscription/show-subscription-dashboard-banner?
                 (profile {:type "unlimited" :status "unpaid" :quantity 2} 2)))))

(t/deftest an-enterprise-plan-does-not-show-the-banner
  (t/is (false? (subscription/show-subscription-dashboard-banner?
                 (profile {:type "enterprise" :status "active" :quantity 30} 30)))))

(t/deftest the-owner-of-an-unlimited-team-shows-the-members-banner
  (t/is (true? (subscription/show-subscription-members-banner?
                {:permissions {:is-owner true}
                 :subscription {:type "unlimited" :status "active" :seats 2}}
                (profile {:type "unlimited"} 8)))))

(t/deftest a-member-of-an-unlimited-team-does-not-show-the-members-banner
  (t/is (false? (subscription/show-subscription-members-banner?
                 {:permissions {:is-owner false}
                  :subscription {:type "unlimited" :status "active" :seats 2}}
                 (profile {:type "unlimited"} 8)))))

(t/deftest the-owner-with-four-editors-over-the-seats-shows-the-members-banner
  (t/is (true? (subscription/show-subscription-members-banner?
                {:permissions {:is-owner true}
                 :subscription {:type "unlimited" :status "active" :seats 20}}
                (profile {:type "unlimited"} 24)))))

(t/deftest the-owner-with-three-editors-over-the-seats-does-not-show-the-members-banner
  (t/is (false? (subscription/show-subscription-members-banner?
                 {:permissions {:is-owner true}
                  :subscription {:type "unlimited" :status "active" :seats 20}}
                 (profile {:type "unlimited"} 23)))))

(t/deftest the-owner-at-the-seat-limit-does-not-show-the-members-banner
  (t/is (false? (subscription/show-subscription-members-banner?
                 {:permissions {:is-owner true}
                  :subscription {:type "unlimited" :status "active" :seats 25}}
                 (profile {:type "unlimited"} 40)))))

(t/deftest the-owner-of-an-enterprise-team-does-not-show-the-members-banner
  (t/is (false? (subscription/show-subscription-members-banner?
                 {:permissions {:is-owner true}
                  :subscription {:type "enterprise" :status "active" :seats 2}}
                 (profile {:type "enterprise"} 40)))))

(t/deftest a-members-banner-without-usage-data-does-not-show
  (t/is (false? (subscription/show-subscription-members-banner?
                 {:permissions {:is-owner true}
                  :subscription {:type "unlimited" :status "active" :seats 2}}
                 (profile {:type "unlimited"})))))
