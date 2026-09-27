;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.organization-team-switch-test
  (:require
   [app.common.uri :as u]
   [app.main.data.nitrate :as dnt]
   [app.main.ui.dashboard.organization-team-switch :as dts]
   [cljs.test :as t :include-macros true]))

;; Regression coverage for the two-level organization/team switcher
;; story: ordering rules, the closed control's second line, the
;; create-team destination, and the no-op team selection. All the
;; decisions live in pure fns, so these tests are data-in,
;; assertions-out, with no DOM.

(t/deftest organization-teams-sort-alphabetically-with-personal-projects-last
  (let [teams [{:id "t1" :name "Design"}
               {:id "t2" :name "Personal projects" :is-default true}
               {:id "t3" :name "Analytics" :is-default false}
               {:id "t4" :name "beta"}]]
    (t/is (= ["Analytics" "beta" "Design" "Personal projects"]
             (map :name (dts/sort-organization-teams teams))))))

(t/deftest all-teams-sort-personal-projects-last-even-when-alphabetically-early
  ;; Display-name-only ordering would put "Personal projects" between
  ;; "Analytics" and "Zebra"; the default team must sort last instead.
  (let [display-name (fn [team] (if (:is-default team) "Personal projects" (:name team)))
        teams [{:id "t1" :name "Zebra"}
               {:id "t2" :name "Personal projects" :is-default true}
               {:id "t3" :name "Analytics"}]]
    (t/is (= ["Analytics" "Zebra" "Personal projects"]
             (map :name (dts/sort-all-teams teams display-name))))))

(t/deftest organizations-sort-alphabetically-with-other-teams-last
  ;; "alpha"/"Beta" check case-insensitive alphabetical order, the two
  ;; "Beta" orgs check the stable :id tie-break, and the nil entry is
  ;; the "Other teams" bucket, which always closes the list.
  (let [organizations [{:id "org-c" :name "Beta"}
                       nil
                       {:id "org-a" :name "alpha"}
                       {:id "org-b" :name "Beta"}]]
    (t/is (= ["org-a" "org-b" "org-c" nil]
             (map :id (dts/sort-organizations organizations))))))

(t/deftest closed-control-line-2-matrix
  (t/testing "profile with organizations, destination has an organization"
    (t/is (= "Org A"
             (dts/closed-control-line-2 true {:id "org-a" :name "Org A"}))))

  (t/testing "profile with organizations, destination has none: line absent"
    (t/is (nil? (dts/closed-control-line-2 true nil))))

  (t/testing "profile with no organizations at all: line absent"
    (t/is (nil? (dts/closed-control-line-2 false nil)))))

(t/deftest create-team-targets-the-previewed-organization
  (let [organizations {"org-a" {:id "org-a" :name "Org A" :default-team-id "team-a-default"}
                       "org-b" {:id "org-b" :name "Org B" :default-team-id "team-b-default"}
                       nil nil}
        other-teams-id (dts/organization-bucket-id nil)]
    (t/testing "target is the previewed organization's default team"
      (t/is (= "team-a-default" (dts/create-team-target-id organizations "org-a")))
      (t/is (= "team-b-default" (dts/create-team-target-id organizations "org-b"))))

    (t/testing "previewed group is Other teams: no organization target"
      (t/is (nil? (dts/create-team-target-id organizations other-teams-id))))))

(t/deftest teams-for-organization-filters-and-sorts-per-organization
  (let [personal-bucket-id (dts/organization-bucket-id nil)
        teams {"t1" {:id "t1" :name "Design" :organization {:id "org-a"}}
               "t2" {:id "t2" :name "Analytics" :organization {:id "org-a"}}
               "t3" {:id "t3" :name "Personal projects" :is-default true}
               "t4" {:id "t4" :name "Other org's team" :organization {:id "org-b"}}
               "t5" {:id "t5" :name "org-b default" :is-default true :organization {:id "org-b"}}
               "t6" {:id "t6" :name "org-d default" :is-default true :organization {:id "org-d"}}}]
    (t/testing "matching organization id: only that organization's teams, sorted"
      (t/is (= ["Analytics" "Design"]
               (map :name (dts/teams-for-organization teams "org-a")))))

    (t/testing "personal bucket: only teams with no organization of their own"
      (t/is (= ["t3"]
               (map :id (dts/teams-for-organization teams personal-bucket-id)))))

    (t/testing "organization with a non-default and a default team: default sorts last"
      (t/is (= ["Other org's team" "org-b default"]
               (map :name (dts/teams-for-organization teams "org-b")))))

    (t/testing "organization with only its own default team"
      (t/is (= ["org-d default"]
               (map :name (dts/teams-for-organization teams "org-d")))))

    (t/testing "no matching teams: empty result"
      (t/is (= [] (dts/teams-for-organization teams "org-c"))))))

(t/deftest team-href-puts-the-route-in-the-query-string-not-the-fragment
  (let [router #{:dashboard-recent}
        team {:id "team-1"}
        href (dts/team-href router team)
        parsed (u/uri href)
        query (u/query-string->map (:query parsed))]
    (t/is (nil? (:fragment parsed)))
    (t/is (= "dashboard-recent" (:screen query)))
    (t/is (= "team-1" (:team-id query)))))

(t/deftest selecting-the-current-team-is-a-no-op
  (t/is (nil? (dts/team-select-target "team-1" {:id "team-1"})))
  (t/is (= "team-2" (dts/team-select-target "team-2" {:id "team-1"}))))

(t/deftest create-organization-in-teams-column-requires-admin-console-flag
  (t/testing "flag present: fallback action offered"
    (t/is (true? (dts/show-create-organization-in-teams-column? #{:admin-console}))))

  (t/testing "flag absent: fallback action hidden"
    (t/is (false? (dts/show-create-organization-in-teams-column? #{})))))

(t/deftest show-subscription-badge-matrix
  (t/testing "default team: never, regardless of its own subscription"
    (t/is (false? (dts/show-subscription-badge? {:is-default true
                                                 :subscription {:type "unlimited"}}))))

  (t/testing "team in an organization: never, regardless of its own subscription"
    (t/is (false? (dts/show-subscription-badge? {:organization {:id "org-a"}
                                                 :subscription {:type "unlimited"}}))))

  (t/testing "standalone team outside every organization: shown for unlimited/enterprise"
    (t/is (true? (dts/show-subscription-badge? {:subscription {:type "unlimited"}})))
    (t/is (true? (dts/show-subscription-badge? {:subscription {:type "enterprise"}}))))

  (t/testing "standalone team with a professional plan: not shown"
    (t/is (false? (dts/show-subscription-badge? {:subscription {:type "professional"}})))))

(t/deftest admin-console-href-resolution-matrix
  (t/testing "owner of the previewed organization: organization-specific href"
    (let [organization {:id "org-a" :slug "org-a-slug" :owner-id "profile-1"}
          profile {:id "profile-1"}]
      (t/is (= (dnt/build-admin-console-href {:organization-id "org-a"
                                              :organization-slug "org-a-slug"})
               (dts/resolve-admin-console-href organization profile)))))

  (t/testing "non-owner of the previewed organization: generic href"
    (let [organization {:id "org-a" :slug "org-a-slug" :owner-id "profile-1"}
          profile {:id "profile-2"}]
      (t/is (= (dnt/build-admin-console-href)
               (dts/resolve-admin-console-href organization profile)))))

  (t/testing "no organization previewed: generic href"
    (t/is (= (dnt/build-admin-console-href)
             (dts/resolve-admin-console-href nil {:id "profile-1"})))))
