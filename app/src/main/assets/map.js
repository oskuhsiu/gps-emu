'use strict';
const map=L.map('map',{zoomControl:false}).setView([0,0],2);
const tiles=L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a> | <a href="https://www.openstreetmap.org/fixthemap">修正地圖</a><br>步行路由：FOSSGIS / OSRM'}).addTo(map);
tiles.on('tileerror',()=>{document.getElementById('tile-error').hidden=false});
tiles.on('tileload',()=>{document.getElementById('tile-error').hidden=true});
const points=L.layerGroup().addTo(map);
const line=L.polyline([],{color:'#087e78',weight:5,opacity:0.9}).addTo(map);
let current=null,real=null,realAccuracy=null,editable=false;
map.on('click',e=>{if(editable)RouteMock.addPoint(e.latlng.lat,e.latlng.lng)});
window.showDraft=(waypoints,route,fit)=>{
 points.clearLayers();
 waypoints.forEach((p,i)=>L.marker(p,{icon:L.divIcon({html:String(i+1),className:'point',iconSize:[24,24],iconAnchor:[12,12]})}).addTo(points));
 line.setLatLngs(route);
 const bounds=route.length?route:waypoints;
 if(fit&&bounds.length>1)map.fitBounds(L.latLngBounds(bounds),{padding:[24,24],maxZoom:17});
 else if(fit&&bounds.length===1)map.setView(bounds[0],16);
};
window.showPosition=(lat,lon)=>{if(!current)current=L.circleMarker([lat,lon],{radius:8,weight:3,color:'white',fillColor:'#e87939',fillOpacity:1}).addTo(map);else if(current.getLatLng().lat!==lat||current.getLatLng().lng!==lon)current.setLatLng([lat,lon]);};
window.clearPosition=()=>{if(current){map.removeLayer(current);current=null;}};
window.showRealPosition=(lat,lon,accuracy,center)=>{
 const point=[lat,lon];
 if(!realAccuracy)realAccuracy=L.circle(point,{radius:accuracy,weight:1,color:'#2878c8',fillOpacity:0.08,interactive:false}).addTo(map);
 else realAccuracy.setLatLng(point).setRadius(accuracy);
 if(!real)real=L.circleMarker(point,{radius:7,weight:3,color:'white',fillColor:'#2878c8',fillOpacity:1,bubblingMouseEvents:false}).bindTooltip('真實位置').addTo(map);
 else real.setLatLng(point);
 if(center)map.setView(point,16);
};
window.clearRealPosition=()=>{if(real){map.removeLayer(real);real=null;}if(realAccuracy){map.removeLayer(realAccuracy);realAccuracy=null;}};
window.setEditable=value=>{editable=value;};
