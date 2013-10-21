(function() {
	//This polling localStorage sucks, but window.postMessage does
	//not work in Firefox extensions :(
	checkUpdates();
	function checkUpdates() {
		var chart = localStorage.getItem("chart");
		if (chart != null) {
			chart = JSON.parse(chart);
			if (chart.type == "region_chart") {
				drawRegionPopulationChart(chart.region, chart.title);
			} else if (chart.type == "set_chart_size") {
				updateChartSize(chart.width, chart.height);
			} else if (chart.type == "national_power") {
				drawNationalPowerChart(chart.region, chart.title, chart.visibleNation, chart.showInfluence);
			}
			localStorage.removeItem("chart");
		}
		setTimeout(checkUpdates, 100);
	}

	var activeChart = null;
	function drawNationalPowerChart(region, title, visibleNation, showInfluence) {
		$.get("http://capitalistparadise.com/api/region/wa/?region=" + region, function(data) {
			var categoryTitles = [];
			var endorsements = [];
			var influence = [];
			var playerIndex = -1;
			for (var nation in data) {
				categoryTitles.push(nation);   
			}
			var sortNames = function(a, b) {
				return data[b].endorsements - data[a].endorsements;
			};
			categoryTitles.sort(sortNames);
			for (var i = 0; i < categoryTitles.length; i++) {
				var nation = data[categoryTitles[i]];
				endorsements.push(nation.endorsements);
				influence.push(nation.influence);
				if (playerIndex == -1 && categoryTitles[i].toLowerCase().split(" ").join("_") == visibleNation) {
					playerIndex = i;
				}
				categoryTitles[i] = "<b>" + categoryTitles[i] + "</b>";
			}
			if (activeChart != null) {
				activeChart.destroy();
			}
			var series;
			if (showInfluence) {
				series = [{	name: 'Influence', data: influence, color: '#AA4643' },{
							name: 'Endorsements', data: endorsements, color: '#4572A7' }]
			} else {
				series = [{ name: 'Endorsements', data: endorsements, color: '#4572A7' }]
			}
			var container = $('<div>');
			chart = new Highcharts.Chart({
				chart: {
					type: 'bar',
					renderTo: container[0],
					width: $("#" + (showInfluence ? 'influence' : 'power')).width(),
					height: Math.max(300, 100 + (categoryTitles.length * 26 * (showInfluence ? 2 : 1))),
					backgroundColor: 'rgba(255, 255, 255, ' + (document.head.innerHTML.indexOf("ns.dark") != -1 ? '0.1' : '1.0') + ')'
				},
				title: {
					text: 'World Assembly Endorsements',
					color: (document.head.innerHTML.indexOf("ns.dark") != -1 ? '#D0D0D0' : '#000000')
				},
				subtitle: {
					text: 'Region: ' + title,
					color: (document.head.innerHTML.indexOf("ns.dark") != -1 ? '#D0D0D0' : '#000000')
				},
				xAxis: {
					categories: categoryTitles,
					title: {
						text: null
					}
				},
				yAxis: {
					min: 0,
					title: {
						text: 'Endorsements',
						align: 'high'
					},
					labels: {
						overflow: 'justify',
						useHTML: true
					},
				},
				plotOptions: {
					bar: {
						dataLabels: {
							enabled: true
						}
					},
					series: {
						cursor: 'pointer',
						point: {
							events: {
								click: function() {
									var nation = this.category.substring(3, this.category.length - 4);
									window.location.href = "http://www.nationstates.net/nation=" + nation.toLowerCase().split(" ").join("_") + "/detail=wa_stats/stats=" + (showInfluence ? 'influence' : 'power');
								}
							}
						}
					}
				},
				credits: {
					enabled: false
				},
				series: series
			});
			activeChart = chart;
			if (playerIndex > -1) {
				for (var i = 0; i < chart.series.length; i++) {
					chart.series[i].data[playerIndex].update({
						color: "#FF0000"
					});
				}
			}
			setTimeout(function() { $("#snark").remove(); container.appendTo($("#" + (showInfluence ? 'influence' : 'power')));}, 2000);
		});
	}

	function updateChartSize(width, height) {
		for (var i = 0; i < Highcharts.charts.length; i++) {
			if (Highcharts.charts[i] != null)
				Highcharts.charts[i].setSize(width, height, true);
		}
	}

	function drawRegionPopulationChart(region, title) {
		$.get("http://capitalistparadise.com/api/region/population/?region=" + region, function(data) {
			var populations = [];
			for (var i = data.region.length - 1; i >= 0; i--) {
				var element = [];
				element.push(data.region[i].timestamp);
				element.push(data.region[i].population);
				populations.push(element);
			}
			chart = new Highcharts.Chart({
				chart: {
					type: 'line',
					renderTo: 'regional-pop',
					backgroundColor: 'rgba(255, 255, 255, ' + (document.head.innerHTML.indexOf("ns.dark") != -1 ? '0.1' : '1.0') + ')'
				},
				title: {
					text: 'Regional Population',
					color: (document.head.innerHTML.indexOf("ns.dark") != -1 ? '#D0D0D0' : '#000000')
				},
				subtitle: {
					text: title,
					color: (document.head.innerHTML.indexOf("ns.dark") != -1 ? '#D0D0D0' : '#000000')
				},
				xAxis: {
					dateTimeLabelFormats: {
						month: '%e. %b',
						year: '%b'
					},
					type: 'datetime',
					title: {
						text: null
					}
				},
				yAxis: {
					min: 0,
					title: {
						text: 'Population',
						align: 'high'
					},
					labels: {
						overflow: 'justify',
						useHTML: true
					}
				},
				credits: {
					enabled: false
				},
				series:  [{ name: 'Population', data: populations, color: '#4572A7' }]
			});
		});
	}
})();