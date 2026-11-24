;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-branches-test
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.common :as dcm]
   [app.main.data.modal :as modal]
   [app.main.data.persistence :as dwp]
   [app.main.data.workspace.branch-reviews :as dwbr]
   [app.main.data.workspace.branches :as dwb]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(def ^:private conflict-revn
  "Main's revn in the conflict answer of the faked merge/update command."
  7)

(defn- cmd-stub
  "Fake `rp/cmd!` that records every call into `calls`. The branch diff
  fetch never answers, so the diff stays loading. A merge or update without
  resolutions answers with one conflict computed at `conflict-revn`; one
  with resolutions answers nothing."
  [calls conflict-id]
  (mock/stub (fn [cmd params]
               (swap! calls conj [cmd params])
               (cond
                 (= cmd :get-file-branch-diff)    (rx/subject)
                 (seq (:resolutions params)) (rx/empty)
                 :else                       (rx/of {:status :conflicts
                                                     :conflicts [{:id conflict-id}]
                                                     :source-revn conflict-revn})))))

(defn- make-store
  []
  (ptk/store {:state {}
              :on-error (fn [cause] (t/is false (str cause)))}))

(defn- branch-cmd
  [mode]
  (if (= mode :update) :update-file-branch-from-source :merge-file-branch))

(defn- open-conflicts!
  "Ask for a `mode` (`:merge` or `:update`) of `branch` and let the conflict
  answer seed the store, as the compare mode then fetches the diff in the
  direction it resolves (`app.main.data.workspace.compare/start-resolve`)."
  [store mode branch]
  (ptk/emit! store (if (= mode :update)
                     (dwb/update-branch-from-main branch)
                     (dwb/merge-branch branch)))
  (ptk/emit! store (dwb/fetch-branch-diff (:id branch)
                                          (if (= mode :update) :source->branch :branch->source))))

(defn- apply-resolutions!
  "Resolve every conflict to main and apply, as the compare mode does."
  [store mode branch]
  (ptk/emit! store (dwb/set-conflict-resolutions
                    (into {}
                          (map (fn [conflict] [(:id conflict) :source]))
                          (get-in @store [:workspace-branch-conflicts :conflicts]))))
  (let [resolutions (get-in @store [:workspace-branch-diff :resolutions])]
    (ptk/emit! store (if (= mode :update)
                       (dwb/update-branch-from-main branch {:resolutions resolutions})
                       (dwb/merge-branch branch {:resolutions resolutions})))))

(defn- last-params
  "Params of the last merge or update call recorded in `calls`."
  [calls mode]
  (->> @calls
       (filter #(= (branch-cmd mode) (first %)))
       (last)
       (second)))

(t/deftest resolutions-applied-before-the-diff-loads-send-the-conflict-revn
  (t/async done
    (let [calls       (atom [])
          conflict-id (uuid/next)]
      (mock/with-mocks
        {rp/cmd! (cmd-stub calls conflict-id)}
        (fn [done']
          (doseq [mode [:merge :update]]
            (t/testing (name mode)
              (let [branch {:id (uuid/next)}
                    store  (make-store)]
                (reset! calls [])
                (open-conflicts! store mode branch)
                (t/is (= :loading (get-in @store [:workspace-branch-diff :status]))
                      "the diff fetch has not answered")
                (apply-resolutions! store mode branch)
                (let [params (last-params calls mode)]
                  (t/is (= {conflict-id :source} (:resolutions params)))
                  (t/is (= conflict-revn (:expected-source-revn params))
                        "the resolutions carry the revn of the conflict answer"))
                (rx/dispose! store))))
          (done'))
        done))))

(t/deftest the-loaded-diff-revn-wins-over-the-conflict-revn
  (t/async done
    (let [calls       (atom [])
          conflict-id (uuid/next)]
      (mock/with-mocks
        {rp/cmd! (cmd-stub calls conflict-id)}
        (fn [done']
          (doseq [mode [:merge :update]]
            (t/testing (name mode)
              (let [branch {:id (uuid/next)}
                    store  (make-store)]
                (reset! calls [])
                (open-conflicts! store mode branch)
                ;; the diff fetch answers, computed at a newer main
                (ptk/emit! store (dwb/update-branch-diff {:status :loaded
                                                          :diff {:meta {:source-revn 9}}}))
                (apply-resolutions! store mode branch)
                (t/is (= 9 (:expected-source-revn (last-params calls mode))))
                (rx/dispose! store))))
          (done'))
        done))))

(t/deftest no-resolutions-without-a-loaded-diff-send-no-revn
  (t/async done
    (let [calls       (atom [])
          conflict-id (uuid/next)]
      (mock/with-mocks
        {rp/cmd! (cmd-stub calls conflict-id)}
        (fn [done']
          (doseq [mode [:merge :update]]
            (t/testing (name mode)
              (let [branch {:id (uuid/next)}
                    store  (make-store)]
                (reset! calls [])
                (open-conflicts! store mode branch)
                (t/is (not (contains? (last-params calls mode) :expected-source-revn))
                      "the first request has neither resolutions nor a diff")
                ;; ask again while the diff is loading, with the conflict
                ;; answer's revn already stored
                (ptk/emit! store (if (= mode :update)
                                   (dwb/update-branch-from-main branch)
                                   (dwb/merge-branch branch)))
                (let [params (last-params calls mode)]
                  (t/is (= 2 (count (filter #(= (branch-cmd mode) (first %)) @calls))))
                  (t/is (not (contains? params :resolutions)))
                  (t/is (not (contains? params :expected-source-revn))
                        "the stored revn only travels with resolutions"))
                (rx/dispose! store))))
          (done'))
        done))))

;;; --- Selective merge: the per-change selection ---

(defn- excluded
  "The per-change selection of the direction the diff of `store` shows."
  [store]
  (dwb/diff-excluded (:workspace-branch-diff @store)))

(t/deftest the-per-change-selection-toggles-and-clears
  (let [store (make-store)
        k1 "color//aaaaaaaa/1"
        k2 "color//aaaaaaaa/2"]
    (t/testing "a row flips on its own"
      (ptk/emit! store (dwb/toggle-diff-excluded #{k1}))
      (t/is (= #{k1} (excluded store)))
      (ptk/emit! store (dwb/toggle-diff-excluded #{k1}))
      (t/is (= #{} (excluded store))))

    (t/testing "a group sets every key of it to one side"
      (ptk/emit! store (dwb/toggle-diff-excluded #{k1 k2} false))
      (t/is (= #{k1 k2} (excluded store)))
      (ptk/emit! store (dwb/toggle-diff-excluded #{k1} false))
      (t/is (= #{k1 k2} (excluded store)))
      (ptk/emit! store (dwb/toggle-diff-excluded #{k1 k2} true))
      (t/is (= #{} (excluded store))))

    (t/testing "leaving the comparison drops the selection"
      (ptk/emit! store (dwb/toggle-diff-excluded #{k1}))
      (ptk/emit! store (dwb/clear-diff-excluded))
      (t/is (= #{} (excluded store))))))

(t/deftest each-direction-keeps-its-own-selection
  (t/async done
    (let [calls  (atom [])
          branch {:id (uuid/next)}
          k1     "color//aaaaaaaa/1"
          k2     "color//aaaaaaaa/2"]
      (mock/with-mocks
        {rp/cmd! (cmd-stub calls (uuid/next))}
        (fn [done']
          (let [store (make-store)]
            (ptk/emit! store (dwb/fetch-branch-diff (:id branch)))
            (ptk/emit! store (dwb/toggle-diff-excluded #{k1}))
            (t/testing "the same branch and direction refetches over the selection"
              (ptk/emit! store (dwb/fetch-branch-diff (:id branch)))
              (t/is (= #{k1} (excluded store))))
            (t/testing "the other direction has a selection of its own"
              (ptk/emit! store (dwb/fetch-branch-diff (:id branch) :source->branch))
              (t/is (= #{} (excluded store)))
              (ptk/emit! store (dwb/toggle-diff-excluded #{k2}))
              (t/is (= #{k2} (excluded store))))
            (t/testing "going back finds the first one as it was left"
              (ptk/emit! store (dwb/fetch-branch-diff (:id branch)))
              (t/is (= #{k1} (excluded store))))
            (t/testing "another branch starts over"
              (ptk/emit! store (dwb/fetch-branch-diff (uuid/next)))
              (t/is (= #{} (excluded store))))
            (rx/dispose! store))
          (done'))
        done))))

(t/deftest the-selection-travels-with-the-merge-and-update-calls
  (t/async done
    (let [calls       (atom [])
          conflict-id (uuid/next)]
      (mock/with-mocks
        {rp/cmd! (cmd-stub calls conflict-id)}
        (fn [done']
          (doseq [mode [:merge :update]]
            (t/testing (name mode)
              (let [branch   {:id (uuid/next)}
                    store    (make-store)
                    excluded #{"color//aaaaaaaa/1"}]
                (reset! calls [])
                (ptk/emit! store (if (= mode :update)
                                   (dwb/update-branch-from-main branch {:excluded excluded})
                                   (dwb/merge-branch branch {:excluded excluded})))
                (t/is (= excluded (:excluded (last-params calls mode))))
                (t/testing "an empty selection sends no `:excluded` at all"
                  (ptk/emit! store (if (= mode :update)
                                     (dwb/update-branch-from-main branch)
                                     (dwb/merge-branch branch)))
                  (t/is (not (contains? (last-params calls mode) :excluded))))
                (rx/dispose! store))))
          (done'))
        done))))

(t/deftest the-comment-travels-with-the-merge-and-its-conflicts
  (t/async done
    (let [calls       (atom [])
          conflict-id (uuid/next)]
      (mock/with-mocks
        {rp/cmd! (cmd-stub calls conflict-id)}
        (fn [done']
          (let [branch {:id (uuid/next)}
                store  (make-store)]
            (ptk/emit! store (dwb/merge-branch branch {:comment "  Ships the header  "}))
            (t/is (= "Ships the header" (:comment (last-params calls :merge)))
                  "the comment is trimmed")
            (t/testing "the conflict answer keeps the comment for the retry"
              (t/is (= "  Ships the header  "
                       (get-in @store [:workspace-branch-conflicts :comment]))))
            (t/testing "a blank comment sends no `:comment` at all"
              (ptk/emit! store (dwb/merge-branch branch {:comment "   "}))
              (t/is (not (contains? (last-params calls :merge) :comment))))
            (rx/dispose! store))
          (done'))
        done))))

;;; --- Review marks: kept for the profile, dropped when the change moves ---

(def ^:private title
  {:kind :shape :status :modified :id (uuid/next) :key "shape/p/title"
   :changed-attrs {:opacity {:source 1 :branch 0.5}}})

(def ^:private logo
  {:kind :shape :status :modified :id (uuid/next) :key "shape/p/logo"
   :changed-attrs {:rx {:source 0 :branch 8}}})

(defn- review-stub
  "Fake `rp/cmd!` for a comparison that lists `title` and `logo`: the diff
  answers at once, the marks answer with `marks`, and keeping a mark
  fails when `fail-set?` holds. Every call is recorded into `calls`."
  [calls marks fail-set?]
  (mock/stub (fn [cmd params]
               (swap! calls conj [cmd params])
               (case cmd
                 :get-file-branch-diff    (rx/of {:changes [title logo] :conflicts []})
                 :get-branch-change-marks (rx/of @marks)
                 :set-branch-change-mark  (if fail-set?
                                            (rx/throw (ex-info "refused" {}))
                                            (rx/of nil))
                 (rx/empty)))))

(defn- last-call
  "Params of the last call of `cmd` recorded in `calls`."
  [calls cmd]
  (->> @calls (filter #(= cmd (first %))) (last) (second)))

(defn- viewed
  [store]
  (get-in @store [:workspace-branch-diff :viewed]))

(t/deftest ^:async a-mark-counts-while-its-change-is-what-it-was
  (let [calls  (atom [])
        branch {:id (uuid/next)}
        marks  (atom [{:change-key (:key title) :fingerprint (dwb/change-fingerprint title)}
                      ;; the logo changed again after it was marked
                      {:change-key (:key logo) :fingerprint "what-it-was-before"}
                      ;; a change the comparison no longer lists
                      {:change-key "shape/p/gone" :fingerprint "x"}])]
    (await
     (mock/with-mocks* {rp/cmd! (review-stub calls marks false)}
       (let [store (make-store)]
         (ptk/emit! store (dwb/fetch-branch-diff (:id branch)))
         (await (async/wait-for-store store #(seq (viewed store)) "the marks load"))
         (t/is (= #{(:key title)} (viewed store)))
         (t/is (= {:branch-id (:id branch) :direction :branch->source}
                  (last-call calls :get-branch-change-marks))
               "the marks are the ones of the direction compared")
         (rx/dispose! store))))))

(t/deftest ^:async marking-a-change-keeps-it-with-its-fingerprint
  (let [calls  (atom [])
        branch {:id (uuid/next)}
        marks  (atom [])]
    (await
     (mock/with-mocks* {rp/cmd! (review-stub calls marks false)}
       (let [store (make-store)]
         (ptk/emit! store (dwb/fetch-branch-diff (:id branch)))
         (await (async/wait-for #(some? (last-call calls :get-branch-change-marks))
                                "the marks are asked for"))
         (ptk/emit! store (dwb/toggle-diff-viewed (:key logo) true))
         (t/is (= #{(:key logo)} (viewed store)) "the mark shows at once")
         (await (async/wait-for #(some? (last-call calls :set-branch-change-mark))
                                "the mark is kept"))
         (t/is (= {:branch-id (:id branch)
                   :direction :branch->source
                   :change-key (:key logo)
                   :fingerprint (dwb/change-fingerprint logo)
                   :viewed true}
                  (last-call calls :set-branch-change-mark)))
         (ptk/emit! store (dwb/toggle-diff-viewed (:key logo)))
         (t/is (= #{} (viewed store)) "without a side it flips")
         (await (async/wait-for #(false? (:viewed (last-call calls :set-branch-change-mark)))
                                "the mark is cleared"))
         (rx/dispose! store))))))

(t/deftest ^:async a-mark-that-cannot-be-kept-does-not-stay
  (let [calls  (atom [])
        branch {:id (uuid/next)}
        marks  (atom [])]
    (await
     (mock/with-mocks* {rp/cmd! (review-stub calls marks true)}
       (let [store (make-store)]
         (ptk/emit! store (dwb/fetch-branch-diff (:id branch)))
         (await (async/wait-for #(some? (last-call calls :get-branch-change-marks))
                                "the marks are asked for"))
         (ptk/emit! store (dwb/toggle-diff-viewed (:key title) true))
         (await (async/wait-for-store store #(empty? (viewed store))
                                      "the marks are read again"))
         (t/is (= #{} (viewed store)))
         (rx/dispose! store))))))

(t/deftest the-fingerprint-follows-what-the-change-is
  (t/is (= (dwb/change-fingerprint title) (dwb/change-fingerprint (assoc title :label "Title"))))
  (t/is (not= (dwb/change-fingerprint title)
              (dwb/change-fingerprint (assoc-in title [:changed-attrs :opacity :branch] 0.6))))
  (t/is (not= (dwb/change-fingerprint title)
              (dwb/change-fingerprint (assoc title :status :deleted)))))

;;; --- Branch switcher: the entry point of the menu and the shortcut ---

(defn- make-store-with
  [state]
  (ptk/store {:state state
              :on-error (fn [cause] (t/is false (str cause)))}))

(def ^:private file-id (uuid/next))

(def ^:private main-without-branches
  {:current-file-id file-id
   :files {file-id {:id file-id :name "Checkout"}}
   :workspace-branch-count {:file-id file-id :count 0}
   :workspace-branch-context {:file-id file-id :loaded? true :info nil}})

(def ^:private main-with-branches
  (assoc main-without-branches :workspace-branch-count {:file-id file-id :count 1}))

(def ^:private on-a-branch
  (assoc main-without-branches :workspace-branch-context
         {:file-id file-id :loaded? true :info {:id (uuid/next) :name "feature-login"}}))

(t/deftest the-switcher-only-exists-once-the-file-has-branches
  (t/is (false? (dwb/has-branches? main-without-branches)))
  (t/is (true? (dwb/has-branches? main-with-branches)))
  (t/is (true? (dwb/has-branches? on-a-branch))
        "a branch counts even before the count loads"))

(t/deftest the-listing-never-decides-whether-the-switcher-exists
  (t/is (true? (dwb/has-branches? (assoc main-with-branches :workspace-branches {:status :error})))
        "a failed listing keeps the switcher")
  (t/is (true? (dwb/has-branches? (assoc main-with-branches :workspace-branches {:status :loading})))
        "a loading listing keeps the switcher")
  (t/is (false? (dwb/has-branches? (assoc main-without-branches :workspace-branches
                                          {:status :loaded :data [{:id (uuid/next)}]})))
        "the count decides, not a listing left over from another file"))

(t/deftest ^:async a-failed-listing-is-an-error-not-an-empty-list
  (await (mock/with-mocks*
           {rp/cmd! (mock/stub (fn [_ _] (rx/throw (ex-info "network" {}))))}
           (let [store (make-store-with main-with-branches)]
             (ptk/emit! store (dwb/init-branches-state))
             (await (async/wait-for #(not= :loading (get-in @store [:workspace-branches :status]))
                                    "the listing settles"))
             (t/is (= :error (get-in @store [:workspace-branches :status])))
             (t/is (nil? (get-in @store [:workspace-branches :data])))
             (t/is (true? (dwb/has-branches? @store)) "the switcher stays")
             (rx/dispose! store)))))

(t/deftest ^:async the-count-loads-without-the-listing
  (let [calls (atom [])]
    (await (mock/with-mocks*
             {rp/cmd! (mock/stub (fn [cmd params]
                                   (swap! calls conj [cmd params])
                                   (rx/of {:count 3})))}
             (let [store (make-store-with (dissoc main-without-branches :workspace-branch-count))]
               (ptk/emit! store (dwb/fetch-branch-count))
               (await (async/wait-for #(some? (:workspace-branch-count @store)) "the count lands"))
               (t/is (= {:file-id file-id :count 3} (:workspace-branch-count @store)))
               (t/is (= [[:get-file-branch-count {:file-id file-id}]] @calls)
                     "only the count is asked, never the listing")
               (t/is (true? (dwb/has-branches? @store)))
               (rx/dispose! store))))))

(t/deftest ^:async the-count-of-another-file-is-dropped-at-once
  (let [other (uuid/next)]
    (await (mock/with-mocks*
             {rp/cmd! (mock/stub (fn [_ _] (rx/subject)))}
             (let [store (make-store-with (assoc main-with-branches :workspace-branch-count
                                                 {:file-id other :count 4}))]
               (ptk/emit! store (dwb/fetch-branch-count))
               (t/is (nil? (:workspace-branch-count @store)))
               (t/is (false? (dwb/has-branches? @store)))
               (rx/dispose! store))))))

(t/deftest ^:async a-failed-count-shows-the-switcher-with-an-error
  (await (mock/with-mocks*
           {cf/flags (conj cf/flags :branching)
            rp/cmd!  (mock/stub (fn [_ _] (rx/throw (ex-info "network" {}))))}
           (let [store (make-store-with (dissoc main-without-branches :workspace-branch-count))]
             (ptk/emit! store (dwb/fetch-branch-count))
             (await (async/wait-for #(some? (:workspace-branch-count @store)) "the count settles"))
             (t/is (= {:file-id file-id :status :error} (:workspace-branch-count @store)))
             (t/is (true? (dwb/branch-count-failed? @store)))
             (t/is (true? (dwb/has-branches? @store))
                   "a count that failed never hides the switcher")
             (ptk/emit! store (dwb/show-branches))
             (await (async/wait-for #(some? (::modal/modal @store))
                                    "the shortcut opens the branches dialog, which retries"))
             (t/is (= :branches-list (get-in @store [::modal/modal :type]))
                   "no create dialog on an unknown count")
             (rx/dispose! store)))))

(t/deftest ^:async a-listing-after-a-failed-count-settles-it
  (await (mock/with-mocks*
           {rp/cmd! (mock/stub (fn [cmd _]
                                 (case cmd
                                   :get-file-branch-info  (rx/of nil)
                                   :get-file-branches     (rx/of [{:id (uuid/next) :status "open"}])
                                   :get-file-branch-count (rx/of {:count 1})
                                   (rx/empty))))}
           (let [store (make-store-with (assoc main-without-branches :workspace-branch-count
                                               {:file-id file-id :status :error}))]
             (ptk/emit! store (dwb/init-branches-state))
             (await (async/wait-for #(some? (get-in @store [:workspace-branch-count :count]))
                                    "the listing reloads the count"))
             (t/is (= {:file-id file-id :count 1} (:workspace-branch-count @store)))
             (t/is (false? (dwb/branch-count-failed? @store)))
             (rx/dispose! store)))))

(t/deftest ^:async a-listing-refreshes-the-count
  (await (mock/with-mocks*
           {rp/cmd! (mock/stub (fn [cmd _]
                                 (case cmd
                                   :get-file-branch-info  (rx/of nil)
                                   :get-file-branches     (rx/of [])
                                   :get-file-branch-count (rx/of {:count 0})
                                   (rx/empty))))}
           (let [store (make-store-with main-with-branches)]
             (ptk/emit! store (dwb/fetch-branches))
             (await (async/wait-for #(= 0 (get-in @store [:workspace-branch-count :count]))
                                    "the listing reloads the count"))
             (t/is (= 0 (get-in @store [:workspace-branch-count :count]))
                   "deleting the last branch from the listing hides the switcher")
             (t/is (false? (dwb/has-branches? @store)))
             (rx/dispose! store)))))

(t/deftest ^:async a-branch-review-from-the-open-branch-saves-first-without-the-listing
  (let [waits     (atom 0)
        branch-id (uuid/next)]
    (await (mock/with-mocks*
             {dwp/wait-persisted (mock/stub (fn [& _] (swap! waits inc) (rx/of :saved)))
              rp/cmd!            (mock/stub (fn [_ _] (rx/empty)))}
             ;; the bottom banner opens the dialog with the branch context;
             ;; the listing only loads with the switcher, so it is absent
             (let [store (make-store-with
                          (assoc main-without-branches :workspace-branch-context
                                 {:file-id file-id
                                  :loaded? true
                                  :info {:id branch-id :branch-file-id file-id}}))]
               (ptk/emit! store (dwbr/create-branch-review branch-id {:title "Review me"}))
               (await (async/wait-for #(pos? @waits) "the pending edits are saved first"))
               (rx/dispose! store))))))

(t/deftest the-switcher-toggles-and-can-be-forced
  (let [store (make-store-with main-with-branches)]
    (ptk/emit! store (dwb/toggle-branch-switcher))
    (t/is (true? (:workspace-branch-switcher @store)))
    (ptk/emit! store (dwb/toggle-branch-switcher))
    (t/is (false? (:workspace-branch-switcher @store)))
    (ptk/emit! store (dwb/toggle-branch-switcher true))
    (ptk/emit! store (dwb/toggle-branch-switcher true))
    (t/is (true? (:workspace-branch-switcher @store)) "forcing it open twice keeps it open")
    (rx/dispose! store)))

(t/deftest ^:async show-branches-opens-the-branches-dialog-when-there-are-branches
  (await (mock/with-mocks*
           {cf/flags (conj cf/flags :branching)}
           (let [store (make-store-with (assoc main-with-branches :workspace-branch-switcher true))]
             (ptk/emit! store (dwb/show-branches))
             (await (async/wait-for #(some? (::modal/modal @store))
                                    "the branches dialog opens"))
             (t/is (= :branches-list (get-in @store [::modal/modal :type])))
             (t/is (false? (:workspace-branch-switcher @store)) "the switcher closes")
             (rx/dispose! store)))))

(t/deftest ^:async show-branches-asks-for-the-first-branch-when-there-are-none
  (await (mock/with-mocks*
           {cf/flags (conj cf/flags :branching)}
           (let [store (make-store-with main-without-branches)]
             (ptk/emit! store (dwb/show-branches))
             (await (async/wait-for #(some? (::modal/modal @store))
                                    "the create dialog opens"))
             (t/is (= :create-branch (get-in @store [::modal/modal :type])))
             (t/is (not (:workspace-branch-switcher @store)))
             (rx/dispose! store)))))

(t/deftest ^:async show-branches-does-nothing-without-the-flag
  (await (mock/with-mocks*
           {cf/flags (disj cf/flags :branching)}
           (let [store (make-store-with main-without-branches)]
             (ptk/emit! store (dwb/show-branches))
             (await (async/settle))
             (t/is (nil? (::modal/modal @store)))
             (t/is (nil? (:workspace-branch-switcher @store)))
             (rx/dispose! store)))))

(t/deftest ^:async opening-a-branch-closes-the-switcher-and-navigates
  (let [navigations (atom [])
        branch-id   (uuid/next)]
    (await (mock/with-mocks*
             {dcm/go-to-workspace
              (fn [& {:as params}]
                (swap! navigations conj params)
                (ptk/data-event ::navigated params))}
             (let [store (make-store-with (assoc main-with-branches :workspace-branch-switcher true))]
               (ptk/emit! store (dwb/open-branch branch-id))
               (await (async/wait-for #(seq @navigations) "it navigates"))
               (t/is (false? (:workspace-branch-switcher @store)))
               (t/is (= {:file-id branch-id} (first @navigations)))
               (rx/dispose! store))))))

;;; --- Creating a branch: one path, from the open file ---

(t/deftest ^:async creating-a-branch-branches-the-open-file
  (let [calls       (atom [])
        branch-id   (uuid/next)
        navigations (atom [])]
    (await (mock/with-mocks*
             {rp/cmd! (mock/stub (fn [cmd params]
                                   (swap! calls conj [cmd params])
                                   (rx/of {:branch-file-id branch-id})))
              dcm/go-to-workspace
              (fn [& {:as params}]
                (swap! navigations conj params)
                (ptk/data-event ::navigated params))}
             (let [store (make-store-with main-without-branches)]
               (ptk/emit! store (dwb/create-branch "feature-login" "from the workspace"))
               (await (async/wait-for #(seq @navigations) "it lands in the new branch"))
               (t/is (= [[:create-file-branch
                          {:file-id file-id
                           :name "feature-login"
                           :description "from the workspace"}]]
                        @calls)
                     "it creates the branch from the open file, once")
               (t/is (= [{:file-id branch-id}] @navigations)
                     "it opens the branch that was created")
               (rx/dispose! store))))))

;;; --- Reviews dialog: the merges into main ---

(t/deftest ^:async the-merges-load-for-the-open-file
  (let [calls  (atom [])
        merges [{:id (uuid/next) :branch-name "header" :comment "Ships it"}]]
    (await (mock/with-mocks*
             {rp/cmd! (mock/stub (fn [cmd params]
                                   (swap! calls conj [cmd params])
                                   (rx/of merges)))}
             (let [store (make-store-with main-without-branches)]
               (ptk/emit! store (dwb/fetch-branch-merges))
               (await (async/wait-for #(= :loaded (get-in @store [:workspace-branch-merges :status]))
                                      "the merges land"))
               (t/is (= merges (get-in @store [:workspace-branch-merges :data])))
               (t/is (= [[:get-file-branch-merges {:file-id file-id}]] @calls)
                     "the open file is asked as is: the command resolves a branch to main")
               (rx/dispose! store))))))

(t/deftest ^:async a-failed-merges-load-is-an-error-not-an-empty-list
  (await (mock/with-mocks*
           {rp/cmd! (mock/stub (fn [_ _] (rx/throw (ex-info "network" {}))))}
           (let [store (make-store-with main-without-branches)]
             (ptk/emit! store (dwb/fetch-branch-merges))
             (await (async/wait-for #(= :error (get-in @store [:workspace-branch-merges :status]))
                                    "the failure lands"))
             (t/is (nil? (get-in @store [:workspace-branch-merges :data])))
             (rx/dispose! store)))))
