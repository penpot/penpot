;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.helpers.persistence
  "Fixture and builders shared by the persistence tests: a store with
  persistence initialized over a mocked transport, synthetic commits, and
  one-page file data to roll back."
  (:require
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.persistence :as dps]
   [app.main.errors :as errors]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.worker :as mw]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(defn local-commit
  "Builds a synthetic local commit event for `file-id`: a page edit as redo,
  no undo, from the `:local` source."
  [file-id]
  (ptk/data-event ::dch/commit
                  {:id (uuid/next)
                   :file-id file-id
                   :file-revn 0
                   :file-vern 0
                   :source :local
                   :features #{}
                   :redo-changes [{:type :mod-page :id (uuid/next) :name "Edited"}]
                   :undo-changes []}))

(defn with-persistence
  "Async fixture for the persistence tests: mocks the transport, flash, the
  worker and the leave-page task set (`:tasks`), and runs `f` with
  persistence initialized.

  Evaluates to a promise resolving once `f` settles and teardown completes;
  `await` it from an `^:async` test.

  By default the transport responds through the `:response` subject. Pass a
  `respond` function (`(fn [cmd params] observable)`) to drive the transport
  response directly instead. Delivery through the subject is asynchronous —
  a promise microtask (`observe-on :asap`), so no timer can race it — and
  the `:answers` atom lists the answers already delivered to the pipeline:
  await each effect — via `wait-for` — before asserting it, and wait on
  `:answers` right after a push when the test must tell a lost answer from
  a hung save."
  [f & [respond]]
  (let [file-id   (uuid/next)
        response  (rx/subject)
        answers   (atom [])
        failures  (atom [])
        requests  (atom [])
        requests$ (rx/subject)
        tasks     (atom #{})
        store     (ptk/store {:state {:current-file-id file-id
                                      :permissions {:can-edit true}
                                      :files {file-id {:id file-id :revn 0}}}
                              :on-error #(t/is false (str %))})]
    (mock/with-mocks*
      {rp/cmd!          (mock/stub (fn [cmd params]
                                     (let [entry [cmd params]]
                                       (swap! requests conj entry)
                                       (rx/push! requests$ entry))
                                     (if respond
                                       (respond cmd params)
                                       (->> response
                                            (rx/take 1)
                                            (rx/observe-on :asap)
                                            (rx/tap #(swap! answers conj %))))))
       errors/flash     (fn [& {:keys [cause]}]
                          (swap! failures conj cause))
       ;; Index updates of applied changes go to a worker the tests lack
       mw/ask!          (mock/stub (fn [_] (rx/empty)))
       st/ongoing-tasks tasks}
      (try
        (ptk/emit! store (dps/initialize-persistence))
        (await (f {:file-id file-id :response response :answers answers
                   :failures failures :requests requests :requests$ requests$
                   :store store :tasks tasks}))
        (finally
          (rx/dispose! store)
          (rx/end! requests$)
          (rx/end! response))))))

(defn ^:async answer!
  "Answers the in-flight save with `value` through the fixture's `response`
  subject and resolves once the pipeline received it. Waiting on `answers`,
  the fixture's record of delivered answers, keeps a dropped delivery
  failing here instead of in the assertions that follow the push.

  The delivery runs in a microtask, so attach the handlers of any promise
  the answer can reject before calling this: a rejection that settles
  during this await is unhandled and fatal to the test process."
  [response answers value]
  (let [before (count @answers)]
    (rx/push! response value)
    (await (async/wait-for #(= (inc before) (count @answers))
                           "the save answer reaches the pipeline"))))

(defn set-attr
  "A change that sets `attr` of shape `shape-id` to `val`."
  [page-id shape-id attr val]
  {:type :mod-obj :id shape-id :page-id page-id
   :operations [{:type :set :attr attr :val val}]})

(defn rename
  "A change that renames shape `shape-id` to `name`."
  [page-id shape-id name]
  (set-attr page-id shape-id :name name))

(defn attr-commit
  "A local commit of `file-id` that sets `attr` of the shape from `from` to
  `to`, with its own undo group."
  [file-id page-id shape-id attr from to]
  (ptk/data-event ::dch/commit
                  {:id (uuid/next)
                   :file-id file-id
                   :file-revn 0
                   :file-vern 0
                   :source :local
                   :features #{}
                   :undo-group (uuid/next)
                   :redo-changes [(set-attr page-id shape-id attr to)]
                   :undo-changes [(set-attr page-id shape-id attr from)]}))

(defn rename-commit
  "A local commit of `file-id` that renames the shape from `from` to `to`,
  with its own undo group."
  [file-id page-id shape-id from to]
  (attr-commit file-id page-id shape-id :name from to))

(defn remote-commit
  "A peer's commit of `changes` to `file-id` at revision `revn`."
  [file-id revn changes]
  (dch/commit {:file-id file-id
               :file-revn revn
               :file-vern 0
               :source :remote
               :save-undo? false
               :redo-changes changes
               :undo-changes []}))

(defn file-data
  "File data with one page holding one rectangle named `name`, with `attrs`
  on top of its defaults."
  [page-id shape-id name & {:as attrs}]
  {:pages [page-id]
   :pages-index {page-id {:id page-id
                          :name "Page"
                          :objects {uuid/zero (cts/setup-shape {:id uuid/zero :type :frame :shapes [shape-id]})
                                    shape-id (merge (cts/setup-shape {:id shape-id :type :rect :name name
                                                                      :parent-id uuid/zero :frame-id uuid/zero})
                                                    attrs)}}}})

(defn add-shape
  "`data` with `shape` added as the last child of the root of `page-id`."
  [data page-id shape]
  (-> data
      (assoc-in [:pages-index page-id :objects (:id shape)] shape)
      (update-in [:pages-index page-id :objects uuid/zero :shapes] conj (:id shape))))

(defn open-file
  "A state update that opens `page-id` of `file-id` with `data` loaded."
  [file-id page-id data]
  #(-> %
       (assoc :current-page-id page-id)
       (assoc-in [:files file-id :data] data)))

(defn open-file-with-library
  "`open-file` that also loads library `lib-id` with `lib-data`."
  [file-id lib-id page-id data lib-data]
  (comp #(assoc-in % [:files lib-id] {:id lib-id :revn 0 :data lib-data})
        (open-file file-id page-id data)))

(defn shape-attr
  [store file-id page-id shape-id attr]
  (get-in @store [:files file-id :data :pages-index page-id :objects shape-id attr]))

(defn shape-name
  [store file-id page-id shape-id]
  (shape-attr store file-id page-id shape-id :name))

(defn undo-entry
  "The undo history entry `commit` would add."
  [commit]
  (let [{:keys [undo-group undo-changes redo-changes]} @commit]
    {:undo-group undo-group
     :undo-groups #{undo-group}
     :undo-changes undo-changes
     :redo-changes redo-changes
     :tags #{}}))

(defn rejection
  "A save answer that rejects `commit-ids`, the first one failing
  validation."
  [& commit-ids]
  {:revn 0
   :rejected {:commit-ids (vec commit-ids)
              :failed-commit-id (first commit-ids)
              :error {:type :validation :code :referential-integrity}}})
