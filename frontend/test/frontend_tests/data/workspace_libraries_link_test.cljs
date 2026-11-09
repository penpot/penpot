;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-libraries-link-test
  "The libraries a file links to while it is open, loaded after the
  workspace was."
  (:require
   [app.common.test-helpers.files :as cthf]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(defn- library-cmd
  "An RPC mock for linking a library whose dependencies are `dependencies`."
  [dependencies]
  (mock/stub
   (fn [command params]
     (case command
       :link-file-to-library (rx/of dependencies)
       :get-file (rx/of {:id (:id params) :name "Library" :data {}})
       :get-file-object-thumbnails (rx/of {})))))

(t/deftest ^:async linking-a-library-marks-it-direct-and-its-dependencies-indirect
  ;; Only the flags fetched when the workspace opens say how a library is
  ;; linked. The ones loaded after that must say it too: the Libraries
  ;; modal reads it to tell which libraries the user can disconnect.
  (let [file       (cthf/sample-file :file1)
        library    (uuid/next)
        dependency (uuid/next)
        store      (ths/setup-store file)]
    (await
     (mock/with-mocks*
       {rp/cmd! (library-cmd [dependency])}
       (ptk/emit! store (dwl/link-file-to-library (:id file) library))
       (await (async/wait-for
               #(every? (fn [id] (some? (get-in @store [:files id :library-of])))
                        [library dependency])
               "both libraries loaded"))
       (t/is (false? (get-in @store [:files library :is-indirect])))
       (t/is (true? (get-in @store [:files dependency :is-indirect])))))))

(defn- deferred-cmd
  "An RPC mock whose :get-file answers only when the test pushes the file
  to the subject it records in `gets`, so the test decides the order."
  [dependencies gets]
  (mock/stub
   (fn [command params]
     (case command
       :link-file-to-library (rx/of (get dependencies (:library-id params)))
       :get-file (let [subject (rx/subject)]
                   (swap! gets conj {:id (:id params) :subject subject})
                   subject)
       :get-file-object-thumbnails (rx/of {})))))

(t/deftest ^:async a-library-loaded-twice-stays-direct
  ;; Link a (which uses c), then b (which uses a) before a has loaded: a is
  ;; requested twice, once because the user linked it and once because b
  ;; brings it in. The user's link has to win whichever answer lands last.
  (let [file    (cthf/sample-file :file1)
        a       (uuid/next)
        b       (uuid/next)
        c       (uuid/next)
        gets    (atom [])
        store   (ths/setup-store file)
        calls   (fn [id] (filterv #(= id (:id %)) @gets))
        answer  (fn [call]
                  (rx/push! (:subject call) {:id (:id call) :name "Library" :data {}}))]
    (await
     (mock/with-mocks*
       {rp/cmd! (deferred-cmd {a [c] b [a c]} gets)}
       (ptk/emit! store (dwl/link-file-to-library (:id file) a))
       (await (async/wait-for #(seq (calls a)) "a requested because it was linked"))
       (ptk/emit! store (dwl/link-file-to-library (:id file) b))
       (await (async/wait-for #(= 2 (count (calls a))) "a requested again through b"))
       (answer (first (calls a)))
       (await (async/wait-for #(some? (get-in @store [:files a :library-of])) "a loaded"))
       (answer (second (calls a)))
       (await (async/settle))
       (t/is (false? (get-in @store [:files a :is-indirect])))))))

(t/deftest ^:async a-direct-flag-from-another-file-does-not-carry-over
  ;; :files outlives a workspace, so a library can still be there as a
  ;; direct link of the file visited before. In this file it is only
  ;; brought in by b.
  (let [file    (cthf/sample-file :file1)
        other   (uuid/next)
        a       (uuid/next)
        b       (uuid/next)
        store   (ths/setup-store file {:libraries [{:id a
                                                    :library-of other
                                                    :is-indirect false}]})]
    (await
     (mock/with-mocks*
       {rp/cmd! (library-cmd [a])}
       (ptk/emit! store (dwl/link-file-to-library (:id file) b))
       (await (async/wait-for
               #(= (:id file) (get-in @store [:files a :library-of]))
               "a loaded for this file"))
       (t/is (true? (get-in @store [:files a :is-indirect])))
       (t/is (false? (get-in @store [:files b :is-indirect])))))))
