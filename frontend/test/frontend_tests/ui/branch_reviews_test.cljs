;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.branch-reviews-test
  "The row of a reviewer names the verdict, and says when it was given on
  an earlier version of the branch. While the branch is the open file,
  its saves make a verdict stale at once. The catalogue here is a
  stand-in for en.po."
  (:require
   [app.common.uuid :as uuid]
   [app.main.ui.workspace.sidebar.branch-reviews :as brui]
   [app.util.i18n :as i18n]
   [cljs.test :as t :include-macros true]))

(def ^:private get-translations @#'i18n/get-translations)

(def ^:private catalogue
  #js {"workspace.branch-review.review-state.approved" "Approved"
       "workspace.branch-review.state.changes-requested" "Changes requested"
       "workspace.branch-review.review-state.pending" "Pending"
       "workspace.branch-review.review-state.stale-approved" "Approved an earlier version"
       "workspace.branch-review.review-state.stale-changes-requested" "Requested changes on an earlier version"})

(defn- with-catalogue
  "Install the stand-in catalogue for one test and put the previous one
  back, so other test namespaces keep their own strings."
  [f]
  (let [translations (get-translations)
        previous     (unchecked-get translations "en")]
    (i18n/set-default-translations catalogue)
    (try
      (f)
      (finally
        (i18n/set-translations "en" previous)))))

(t/use-fixtures :each with-catalogue)

(t/deftest a-current-verdict-names-the-verdict
  (t/is (= "Approved" (brui/verdict-label {:verdict "approved" :stale false})))
  (t/is (= "Changes requested"
           (brui/verdict-label {:verdict "changes-requested" :stale false}))))

(t/deftest a-stale-verdict-says-which-verdict-it-was
  (t/is (= "Approved an earlier version"
           (brui/verdict-label {:verdict "approved" :stale true})))
  (t/is (= "Requested changes on an earlier version"
           (brui/verdict-label {:verdict "changes-requested" :stale true}))))

(t/deftest a-reviewer-without-a-verdict-is-pending
  (t/is (= "Pending" (brui/verdict-label {:verdict nil :stale false}))))

;; The review of the branch file `branch-id`, with one approval given at
;; revision 7 that the server last saw as current.
(def ^:private branch-id (uuid/next))

(def ^:private approval
  {:profile-id (uuid/next) :verdict "approved" :verdict-revn 7 :stale false})

(def ^:private review
  {:id (uuid/next)
   :source-file-id branch-id
   :reviews [approval]
   :overall-verdict "approved"})

(t/deftest a-verdict-on-the-open-revision-is-current
  (let [file {:id branch-id :revn 7}]
    (t/is (false? (brui/verdict-stale? review file approval)))
    (t/is (= "approved" (:overall-verdict (brui/live-verdicts review file))))))

(t/deftest a-save-past-the-verdict-makes-it-stale-at-once
  (let [file {:id branch-id :revn 8}
        live (brui/live-verdicts review file)]
    (t/is (true? (brui/verdict-stale? review file approval)))
    (t/is (true? (:stale (first (:reviews live)))))
    (t/is (= "in-review" (:overall-verdict live))
          "a stale approval only counts as in review")
    (t/is (= [0 1] (brui/current-approvals live)))))

(t/deftest another-open-file-keeps-the-server-staleness
  (let [file {:id (uuid/next) :revn 99}]
    (t/is (false? (brui/verdict-stale? review file approval)))
    (t/is (true? (brui/verdict-stale? review file (assoc approval :stale true))))
    (t/is (identical? review (brui/live-verdicts review file)))))

(t/deftest a-reviewer-without-a-verdict-revision-keeps-the-server-staleness
  (let [file    {:id branch-id :revn 8}
        pending {:profile-id (uuid/next) :verdict nil :verdict-revn nil :stale false}]
    (t/is (false? (brui/verdict-stale? review file pending)))))
