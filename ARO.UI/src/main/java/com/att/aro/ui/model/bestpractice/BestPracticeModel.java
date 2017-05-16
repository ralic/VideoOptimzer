/*
 *  Copyright 2017 AT&T
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package com.att.aro.ui.model.bestpractice;



/* BestPracticeDisplayGroup
 * Header 
 *   AROBestPracticesPanel.getHeaderPanel()
 * DateTraceAppDetailPanel.getDataPanel()
 * AROBpOverallResulsPanel.createTestStatisticsPanel()
 * TESTS CONDUCTED
 * BestPracticeDisplayGroup.getConnectionsSection()
 * BestPracticeDisplayGroup.getFileDownloadSection()
 * BestPracticeDisplayGroup.getHtmlSection()
 * BestPracticeDisplayGroup.getOtherSection()
 */

/*
 * -header-
 * SUMMARY
 *  TEST STATISTICS
 *  TESTS CONDUCTED
 * AROBpDetailedResultPanel[]
 *  FILE DOWNLOAD
 *  CONNECTIONS
 *  HTML
 *  OTHERS
 */

public class BestPracticeModel {
	
	public Summary summary;
	public BpTestStatistics bpTestStatistics;
	public BpTestsConductedModel bpTestsConductedModel;
	
	
	public Summary getSummary() {
		return summary;
	}

	public void setSummary(Summary summary) {
		this.summary = summary;
	}

	public BpTestStatistics getBpTestStatistics() {
		return bpTestStatistics;
	}

	public void setBpTestStatistics(BpTestStatistics bpTestStatistics) {
		this.bpTestStatistics = bpTestStatistics;
	}

	public BpTestsConductedModel getBpTestsConductedModel() {
		return bpTestsConductedModel;
	}

	public void setBpTestsConductedModel(BpTestsConductedModel bpTestsConductedModel) {
		this.bpTestsConductedModel = bpTestsConductedModel;
	}
	
	
	
}
