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
