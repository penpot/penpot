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
                 (= cmd :get-branch-diff)    (rx/subject)
                 (seq (:resolutions params)) (rx/empty)
                 :else                       (rx/of {:status :conflicts
                                                     :conflicts [{:id conflict-id}]
                                                     :main-revn conflict-revn})))))

(defn- make-store
  []
  (ptk/store {:state {}
              :on-error (fn [cause] (t/is false (str cause)))}))

(defn- branch-cmd
  [mode]
  (if (= mode :update) :update-branch-from-main :merge-file-branch))

(defn- open-conflicts!
  "Ask for a `mode` (`:merge` or `:update`) of `branch` and let the conflict
  answer open the modal, which then fetches its own diff in its direction,
  as `branch-conflicts-dialog*` does on mount."
  [store mode branch]
  (ptk/emit! store (if (= mode :update)
                     (dwb/update-branch-from-main branch)
                     (dwb/merge-branch branch)))
  (ptk/emit! store (dwb/fetch-branch-diff (:id branch)
                                          (if (= mode :update) :main->branch :branch->main))))

(defn- apply-resolutions!
  "Resolve every conflict to main and press Apply, as the modal does."
  [store mode branch]
  (ptk/emit! store (dwb/set-all-resolutions :main))
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
                      "the modal's diff fetch has not answered")
                (apply-resolutions! store mode branch)
                (let [params (last-params calls mode)]
                  (t/is (= {conflict-id :main} (:resolutions params)))
                  (t/is (= conflict-revn (:expected-main-revn params))
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
                ;; the modal's diff fetch answers, computed at a newer main
                (ptk/emit! store (dwb/update-branch-diff {:status :loaded
                                                          :diff {:meta {:main-revn 9}}}))
                (apply-resolutions! store mode branch)
                (t/is (= 9 (:expected-main-revn (last-params calls mode))))
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
                (t/is (not (contains? (last-params calls mode) :expected-main-revn))
                      "the first request has neither resolutions nor a diff")
                ;; ask again while the diff is loading, with the conflict
                ;; answer's revn already stored
                (ptk/emit! store (if (= mode :update)
                                   (dwb/update-branch-from-main branch)
                                   (dwb/merge-branch branch)))
                (let [params (last-params calls mode)]
                  (t/is (= 2 (count (filter #(= (branch-cmd mode) (first %)) @calls))))
                  (t/is (not (contains? params :resolutions)))
                  (t/is (not (contains? params :expected-main-revn))
                        "the stored revn only travels with resolutions"))
                (rx/dispose! store))))
          (done'))
        done))))

;;; --- Selective merge: the per-change selection ---

(t/deftest the-per-change-selection-toggles-and-clears
  (let [store (make-store)
        k1 "color//aaaaaaaa/1"
        k2 "color//aaaaaaaa/2"]
    (t/testing "a row flips on its own"
      (ptk/emit! store (dwb/toggle-diff-excluded #{k1}))
      (t/is (= #{k1} (get-in @store [:workspace-branch-diff :excluded])))
      (ptk/emit! store (dwb/toggle-diff-excluded #{k1}))
      (t/is (= #{} (get-in @store [:workspace-branch-diff :excluded]))))

    (t/testing "a group sets every key of it to one side"
      (ptk/emit! store (dwb/toggle-diff-excluded #{k1 k2} false))
      (t/is (= #{k1 k2} (get-in @store [:workspace-branch-diff :excluded])))
      (ptk/emit! store (dwb/toggle-diff-excluded #{k1} false))
      (t/is (= #{k1 k2} (get-in @store [:workspace-branch-diff :excluded])))
      (ptk/emit! store (dwb/toggle-diff-excluded #{k1 k2} true))
      (t/is (= #{} (get-in @store [:workspace-branch-diff :excluded]))))

    (t/testing "closing the compare dialog drops the selection"
      (ptk/emit! store (dwb/toggle-diff-excluded #{k1}))
      (ptk/emit! store (dwb/clear-diff-excluded))
      (t/is (= #{} (get-in @store [:workspace-branch-diff :excluded]))))))

(t/deftest a-refetch-of-the-same-comparison-keeps-the-selection
  (t/async done
    (let [calls (atom [])
          branch {:id (uuid/next)}]
      (mock/with-mocks
        {rp/cmd! (cmd-stub calls (uuid/next))}
        (fn [done']
          (let [store (make-store)]
            (ptk/emit! store (dwb/fetch-branch-diff (:id branch)))
            (ptk/emit! store (dwb/toggle-diff-excluded #{"color//aaaaaaaa/1"}))
            (t/testing "the same branch and direction refetches over the selection"
              (ptk/emit! store (dwb/fetch-branch-diff (:id branch)))
              (t/is (= #{"color//aaaaaaaa/1"}
                       (get-in @store [:workspace-branch-diff :excluded]))))
            (t/testing "the other direction starts the selection over"
              (ptk/emit! store (dwb/fetch-branch-diff (:id branch) :main->branch))
              (t/is (= #{} (get-in @store [:workspace-branch-diff :excluded]))))
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

;;; --- Branch switcher: the entry point of the menu and the shortcut ---

(defn- make-store-with
  [state]
  (ptk/store {:state state
              :on-error (fn [cause] (t/is false (str cause)))}))

(def ^:private file-id (uuid/next))

(def ^:private main-without-branches
  {:current-file-id file-id
   :files {file-id {:id file-id :name "Checkout"}}
   :workspace-branches {:status :loaded :data []}
   :workspace-branch-context {:file-id file-id :loaded? true :info nil}})

(def ^:private main-with-branches
  (assoc main-without-branches :workspace-branches
         {:status :loaded :data [{:id (uuid/next) :name "feature-login" :status "open"}]}))

(def ^:private on-a-branch
  (assoc main-without-branches :workspace-branch-context
         {:file-id file-id :loaded? true :info {:id (uuid/next) :name "feature-login"}}))

(t/deftest the-switcher-only-exists-once-the-file-has-branches
  (t/is (false? (dwb/has-branches? main-without-branches)))
  (t/is (true? (dwb/has-branches? main-with-branches)))
  (t/is (true? (dwb/has-branches? on-a-branch))
        "a branch counts even before the list loads"))

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

(t/deftest ^:async show-branches-opens-the-switcher-when-there-are-branches
  (await (mock/with-mocks*
           {cf/flags (conj cf/flags :branching)}
           (let [store (make-store-with main-with-branches)]
             (ptk/emit! store (dwb/show-branches))
             (await (async/wait-for #(true? (:workspace-branch-switcher @store))
                                    "the switcher opens"))
             (await (async/settle))
             (t/is (nil? (::modal/modal @store)) "no dialog opens")
             (rx/dispose! store)))))

(t/deftest ^:async show-branches-asks-for-the-first-branch-when-there-are-none
  (await (mock/with-mocks*
           {cf/flags (conj cf/flags :branching)}
           (let [store (make-store-with main-without-branches)]
             (ptk/emit! store (dwb/show-branches))
             (await (async/wait-for #(some? (::modal/modal @store))
                                    "the create dialog opens"))
             (t/is (= :create-branch (get-in @store [::modal/modal :type])))
             (t/is (= "Checkout" (get-in @store [::modal/modal :props :file-name])))
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
