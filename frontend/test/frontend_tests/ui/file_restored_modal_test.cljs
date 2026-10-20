;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.file-restored-modal-test
  (:require
   [app.common.time :as ct]
   [app.main.ui.workspace.file-restored-modal :as frm]
   [app.util.i18n :as i18n]
   [cljs.test :as t :include-macros true]))

(defn- message
  "The translation key and arguments of the restore message."
  [fullname label created-at]
  (with-redefs [i18n/tr (fn ([key] [key]) ([key & args] (into [key] args)))]
    (#'frm/restored-message fullname label created-at)))

(t/deftest the-message-names-the-version-by-its-label-or-its-date
  (let [created-at (ct/now)
        date       (ct/format-inst created-at "PPp")]
    (t/is (= ["workspace.file-restored.message-label" "Ada" "Hand-off v2"]
             (message "Ada" "Hand-off v2" created-at)))
    (t/is (= ["workspace.file-restored.message-label-anonymous" "Hand-off v2"]
             (message nil "Hand-off v2" created-at)))
    (t/is (= ["workspace.file-restored.message-date" "Ada" date]
             (message "Ada" nil created-at)))
    (t/is (= ["workspace.file-restored.message-date-anonymous" date]
             (message nil nil created-at)))
    (t/is (= ["workspace.file-restored.message" "Ada"]
             (message "Ada" nil nil)))
    (t/is (= ["workspace.file-restored.message-anonymous"]
             (message nil nil nil)))))
