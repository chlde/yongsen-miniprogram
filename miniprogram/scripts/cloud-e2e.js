const fs=require('fs'),assert=require('assert/strict'),path=require('path');
const {connect,call,wait}=require('./native-tools');
const input={ assignmentId: process.env.E2E_ASSIGNMENT_ID };
if (!input.assignmentId) throw new Error('Set E2E_ASSIGNMENT_ID to a fresh task assigned to the signed-in WeChat account');
const artifacts=path.resolve(__dirname,'../artifacts');fs.mkdirSync(artifacts,{recursive:true});
const evidence={startedAt:new Date().toISOString(),environment:'yongsen-prod-d4ghriorcc1483d38',mode:'real-wechat-native-components',checks:[]};
let mp;
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function tap(page,selector){const element=await page.$(selector);assert.ok(element,selector);await element.tap();}
async function at(route){return wait(route,async()=>{const page=await mp.currentPage();return page&&page.path===route&&page;});}
(async()=>{
 mp=await connect();mp.on('exception',error=>{evidence.exceptions=evidence.exceptions||[];evidence.exceptions.push(String(error.message||error));});
 const me=await call(mp,'me');
 await mp.evaluate(()=>getApp().clearLogin());let page=await mp.reLaunch('/pages/login/index');await tap(page,'#login-button');page=await at('pages/tasks/index');
 await wait('assigned task list',async()=>{const d=await page.data();if(d.error)throw Error(d.error);return d.activeItems.length>0;});
 await mp.screenshot({path:artifacts+'/learner-tasks.png'});
 page=await mp.navigateTo('/pages/task/index?assignmentId='+input.assignmentId);
 await wait('task detail',async()=>{const d=await page.data();if(d.error)throw Error(d.error);return !d.loading&&d.task;});
 const task=(await page.data()).task;
 if(task.status==='COMPLETED')throw new Error('Use a fresh uncompleted assignment for an end-to-end learning run');
 const video=task.materials.find(m=>m.type==='MP4');
 page=await mp.navigateTo(`/pages/learn/index?assignmentId=${input.assignmentId}&taskMaterialId=${video.id}`);
 await wait('video media ready',async()=>{const d=await page.data();if(d.error)throw Error(d.error);return d.mediaReady;});
 if(!(await page.data()).completed){
  await tap(page,'#start-learning');
  await wait('video actual playing',async()=>{const d=await page.data();if(d.error)throw Error(d.error);return d.currentSeconds>=2;});
  for(let pass=0;pass<3;pass++){
   await wait('video end',async()=>{const d=await page.data();if(d.error)throw Error(d.error);return d.completed||(!d.playing&&d.currentSeconds>=11);},30000);
   await delay(1500);if((await page.data()).completed)break;
   const diagnostics=await mp.evaluate(()=>{const p=getCurrentPages().slice(-1)[0];return {progress:p.progress,batches:p.tracker&&p.tracker.diagnostics};});
   fs.writeFileSync(artifacts+`/video-diagnostics-${pass}.json`,JSON.stringify(diagnostics,null,2));
   await tap(page,'#replay-missing');await wait('replay readiness',async()=>(await page.data()).needsResume);await tap(page,'#start-learning');
  }
 }
 await wait('video confirmed progress',async()=>(await page.data()).completed,30000);
 assert.ok((await page.data()).effectiveSeconds>=10);await mp.screenshot({path:artifacts+'/video-completed.png'});
 console.log('PASS: real native video playback and cloud-confirmed coverage');evidence.checks.push('Native 12-second H264 playback; server verified complete coverage');
 await tap(page,'#back-task');page=await at('pages/task/index');await wait('task reload',async()=>!(await page.data()).loading);
 const pdf=(await page.data()).task.materials.find(m=>m.type==='PDF');
 page=await mp.navigateTo(`/pages/learn/index?assignmentId=${input.assignmentId}&taskMaterialId=${pdf.id}`);
 await wait('PDF image ready',async()=>{const d=await page.data();if(d.error)throw Error(d.error);return d.mediaReady;});
 if(!(await page.data()).completed){
  await tap(page,'#start-learning');await delay(5000);
  const scroll=await page.$('#pdf-viewport'),image=await page.$('#pdf-page-image');const imageSize=await image.size(),viewportSize=await scroll.size();
  const overflow=Math.max(0,imageSize.height-viewportSize.height);
  for(let y=70;y<overflow;y+=70){await scroll.scrollTo(0,y);await delay(1300);}
  await scroll.scrollTo(0,overflow+3);
 }
 await wait('PDF thirty seconds and complete visible coverage',async()=>{const d=await page.data();if(d.error)throw Error(d.error);return d.completed;},55000);
 assert.ok((await page.data()).pageSeconds>=30);await mp.screenshot({path:artifacts+'/pdf-completed.png'});
 console.log('PASS: real PDF image reading and cloud-confirmed page time');evidence.checks.push('Native rendered PDF; 30-second page visibility and complete page coverage');
 await tap(page,'#back-task');page=await at('pages/task/index');await wait('ready to confirm',async()=>(await page.data()).task&& (await page.data()).task.status==='READY_TO_CONFIRM');
 await tap(page,'#confirmation-checkbox');await tap(page,'#confirm-button');await wait('completed',async()=>(await page.data()).task.status==='COMPLETED');
 await mp.screenshot({path:artifacts+'/task-completed.png'});
 page=await mp.switchTab('/pages/records/index');await wait('completion record',async()=>(await page.data()).items.some(x=>x.id===input.assignmentId));
 await mp.screenshot({path:artifacts+'/records.png'});evidence.checks.push('Learner checkbox confirmation creates immutable completion and personal record');
 console.log('PASS: explicit confirmation and personal record');
})().catch(error=>{evidence.failure=error.stack;console.error(error.code,error.message);process.exitCode=1;}).finally(async()=>{
 evidence.finishedAt=new Date().toISOString();evidence.status=evidence.failure?'FAILED':'PASSED';fs.writeFileSync(artifacts+'/native-e2e-result.json',JSON.stringify(evidence,null,2));
 if(mp)await mp.disconnect();
});
