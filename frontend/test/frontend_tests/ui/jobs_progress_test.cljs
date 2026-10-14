;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.jobs-progress-test
  "How a job milestone is written: the stage and its counter, and the
  upload stage as a percentage of the file."
  (:require
   [app.main.ui.jobs.progress :as jp]
   [app.util.i18n :as i18n]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]))

(def ^:private stub-tr
  (mock/stub (fn [k] (str "TR:" k))))

(t/deftest a-counter-milestone-writes-its-stage-and-counter
  (with-redefs [i18n/tr stub-tr]
    (t/is (= "TR:jobs.progress.stage.pages 3/8"
             (jp/milestone-text {:stage :pages :counters {:pages {:current 3 :total 8}}})))
    (t/testing "a counter with no total writes the bare current"
      (t/is (= "TR:jobs.progress.stage.pages 3"
               (jp/milestone-text {:stage :pages :counters {:pages {:current 3}}}))))))

(t/deftest a-file-milestone-appends-the-file-counter
  (with-redefs [i18n/tr stub-tr]
    (t/is (= "TR:jobs.progress.stage.pages 3/8 · TR:jobs.progress.stage.files 1/2"
             (jp/milestone-text {:stage    :pages
                                 :counters {:pages {:current 3 :total 8}
                                            :files {:current 1 :total 2}}}
                                :file? true)))))

(t/deftest an-upload-milestone-writes-the-percentage-of-the-file
  (with-redefs [i18n/tr stub-tr]
    (t/testing "the chunks sent become a whole percentage"
      (t/is (= "TR:jobs.progress.stage.upload (33%)"
               (jp/milestone-text {:stage :upload :counters {:upload {:current 1 :total 3}}})))
      (t/is (= "TR:jobs.progress.stage.upload (100%)"
               (jp/milestone-text {:stage :upload :counters {:upload {:current 9 :total 9}}}))))
    (t/testing "an upload with no total keeps the bare label"
      (t/is (= "TR:jobs.progress.stage.upload"
               (jp/milestone-text {:stage :upload :counters {:upload {:current 1}}}))))))
