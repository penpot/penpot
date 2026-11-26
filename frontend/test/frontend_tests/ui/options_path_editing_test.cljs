;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.options-path-editing-test
  "The design panel shows the path edition options only while the edited
  path is on the page. Its edit state can outlive the shape (deleted by
  another user, undone), and the panel crashed on the missing shape."
  (:require
   [app.common.uuid :as uuid]
   [app.main.ui.workspace.sidebar.options :as options]
   [cljs.test :as t :include-macros true]))

(def ^:private path-editing?
  @#'options/path-editing?)

(t/deftest path-editing-while-the-path-is-on-the-page
  (let [id        (uuid/next)
        edit-path {id {:edit-mode :move}}
        objects   {id {:id id :type :path}}]
    (t/is (true? (path-editing? edit-path id objects)))))

(t/deftest no-path-editing-when-the-edited-path-left-the-page
  (let [id        (uuid/next)
        edit-path {id {:edit-mode :move}}]
    (t/is (false? (path-editing? edit-path id {})))))

(t/deftest no-path-editing-without-edit-state
  (let [id      (uuid/next)
        objects {id {:id id :type :path}}]
    (t/is (false? (path-editing? {} id objects)))))
