;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.exports-widget-test
  "The cancel affordance of the assets export widget: local runs and
  server jobs with an id offer it while they did not settle — running
  or still queued, since the backend accepts a cancel for a pending
  job too."
  (:require
   [app.common.uuid :as uuid]
   [app.main.ui.exports.assets :as widget]
   [cljs.test :as t :include-macros true]))

(t/deftest cancellable-allows-running-and-queued-server-jobs
  (let [job-id (uuid/next)]
    (t/is (true? (widget/cancellable? {:in-progress true
                                       :backend     "exporter"
                                       :job-id      job-id
                                       :status      "running"})))
    (t/is (true? (widget/cancellable? {:in-progress true
                                       :backend     "exporter"
                                       :job-id      job-id
                                       :status      "queued"})))))

(t/deftest cancellable-refuses-settled-and-cancelling-widgets
  (let [job-id (uuid/next)]
    (t/is (false? (widget/cancellable? {:in-progress true
                                        :backend     "exporter"
                                        :job-id      job-id
                                        :status      "cancelling"})))
    (t/is (false? (widget/cancellable? {:in-progress false
                                        :backend     "exporter"
                                        :job-id      job-id
                                        :status      "queued"})))
    (t/is (false? (widget/cancellable? {:in-progress true
                                        :backend     "exporter"
                                        :job-id      nil
                                        :status      "running"})))))

(t/deftest cancellable-allows-local-runs
  (t/is (true? (widget/cancellable? {:in-progress true
                                     :backend     "local"
                                     :job-id      nil
                                     :status      "running"}))))
