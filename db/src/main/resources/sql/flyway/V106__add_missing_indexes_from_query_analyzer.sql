-- Copyright 2022 The Nomulus Authors. All Rights Reserved.
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

CREATE INDEX IDX3y3k7m2bkgahm9sixiohgyrga ON "Domain" (transfer_billing_event_id);
CREATE INDEX IDXsfci08jgsymxy6ovh4k7r358c ON "Domain" (billing_recurrence_id);
CREATE INDEX IDXcju58vqascbpve1t7fem53ctl ON "Domain" (transfer_billing_recurrence_id);

CREATE INDEX IDXjw3rwtfrexyq53x9vu7qghrdt ON "DomainHost" (host_repo_id);

CREATE INDEX IDXbgssjudpm428mrv0xfpvgifps ON "GracePeriod" (billing_event_id);
CREATE INDEX IDX5u5m6clpk3nktrvtyy5umacb6 ON "GracePeriod" (billing_recurrence_id);

CREATE INDEX IDX6ebt3nwk5ocvnremnhnlkl6ff ON "BillingEvent" (cancellation_matching_billing_recurrence_id);

CREATE INDEX IDX4ytbe5f3b39trsd4okx5ijhs4 ON "BillingCancellation" (billing_event_id);
CREATE INDEX IDXku0fopwyvd57ebo8bf0jg9xo2 ON "BillingCancellation" (billing_recurrence_id);