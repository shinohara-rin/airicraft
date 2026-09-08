package ai.moeru.airicraft.systemone.voxel;

import ai.moeru.airicraft.systemone.TaskKernel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.systemone.TaskKernel.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionDomain.*;
import static ai.moeru.airicraft.systemone.voxel.ProductionKnowledge.*;
import static ai.moeru.airicraft.systemone.voxel.VoxelObservation.*;

class MissionLightingTest {
	private static final Pos FEET = new Pos(0,1,0);
	private static final ProductionKnowledge BOOK = new ProductionKnowledge("test", List.of(new Recipe("torch","minecraft:torch",4,2,List.of(new Cell(0,"coal"),new Cell(2,"stick")))),
		List.of(new Harvest("log", List.of("log_block"), List.of(), Technique.EXPOSED),new Harvest("coal",List.of("log_block"),List.of(),Technique.EXPOSED)));
	private static TaskKernel<Task,StoneAcquisition.World,VoxelCommand> kernel() {
		return new TaskKernel<>(new ProductionDomain(BOOK),new Limits(16,24,5000,100));
	}
	@Test void completedCobblestoneLeavesTheBranchBeforeLightingSupplyInheritsDependencies() {
		var state=new State<Task>("s","r",List.of(
			new Frame<>(1,new Mission("goal",1,0,0,WorkingLight.begin()),new WaitingChild<>(2,"goal"),Optional.empty(),Optional.empty()),
			new Frame<>(2,new Acquire("pick",1,Map.of(),Set.of("goal"),Set.of(),""),new WaitingChild<>(3,"stone"),Optional.empty(),Optional.empty()),
			new Frame<>(3,new Acquire("cobblestone",3,Map.of(),Set.of("goal","pick"),Set.of(),"harvest"),new Ready<>(),Optional.empty(),Optional.of(Outcome.success("stone collected")))),4,1,5000,0,Optional.empty());
		var kernel=kernel();var dim=world(4,Map.of("cobblestone",3));
		var settled=kernel.advance(state,dim,List.of(),1);
		assertEquals(2,settled.state().stack().size());assertTrue(settled.effects().isEmpty());
		assertTrue(settled.events().stream().anyMatch(e->e.task()==3 && e.type().equals("task_ended") && e.detail().startsWith("SUCCEEDED")));
		var repair=kernel.advance(settled.state(),dim,List.of(),2);
		var supply=repair.state().stack().stream().map(Frame::task).filter(t->t instanceof Acquire a && a.item().equals("minecraft:torch")).map(t->(Acquire)t).findFirst().orElseThrow();
		assertFalse(supply.ancestors().contains("cobblestone"));assertTrue(supply.ancestors().contains("pick"));
	}
	@Test void completedRepairDoesNotExpireWhileReturningThroughWorkingLight() {
		var route=new ArrayList<Pos>();for(int z=0;z<=16;z++)route.add(FEET.offset(0,0,z));
		var saved=new Suspended(Acquire.root("log",1),Optional.empty(),Optional.empty());
		var repair=new ResumeWork(saved,ReturnNavigation.State.begin(route),LightingPolicy.Repair.SUPPLY,Optional.of(Outcome.success("supplied")),9,FEET,SurvivalPolicy.Vitals.healthy());
		var step=kernel().advance(branch(repair,new Ready<>(),WorkingLight.begin()),returnWorld(FEET,15),List.of(),10);
		assertTrue(step.state().outcome().isEmpty(),"completed supply work must not abort return travel at the old repair deadline");
		assertTrue(step.events().stream().noneMatch(e->e.detail().equals("lighting_repair_revoked")));
	}
	@Test void returnTravelCanBeInterruptedByNewDarknessAndResumeAfterRelease() {
		var route=List.of(FEET,FEET.offset(1,0,0),FEET.offset(2,0,0));
		var saved=new Suspended(Acquire.root("log",1),Optional.empty(),Optional.empty());
		var task=new ReturnWork(saved,ReturnNavigation.State.begin(route));
		var kernel=kernel();var first=kernel.advance(branch(task,new Ready<>(),WorkingLight.begin()),world(15,Map.of("minecraft:torch",2)),List.of(),1);
		var travel=(Start<VoxelCommand>)first.effects().getFirst();
		var dark=kernel.advance(first.state(),world(4,Map.of("minecraft:torch",2)),List.of(),2);
		assertEquals(List.of(new Stop<>(travel.token())),dark.effects());
		var stopped=(ResumeWork)dark.state().stack().getLast().task();
		var returning=assertInstanceOf(ReturnWork.class,stopped.saved().task());
		assertEquals(saved,returning.saved());assertTrue(returning.returning().last().isEmpty(),"interrupted navigation is not a failed waypoint");
		var repair=kernel.advance(dark.state(),world(4,Map.of("minecraft:torch",2)),List.of(new Released(travel.token())),3);
		var placement=(Start<VoxelCommand>)repair.effects().getFirst();assertInstanceOf(VoxelCommand.Place.class,placement.command());
		var state=repair.state();List<Feedback> feedback=List.of(new Finished(placement.token(),Outcome.success("placed")));boolean resumed=false;
		for(int tick=4;tick<14;tick++) {
			var step=kernel.advance(state,world(10,Map.of("minecraft:torch",1)),feedback,tick);state=step.state();feedback=List.of();
			for(var effect:step.effects())if(effect instanceof Start<VoxelCommand> start) {
				assertEquals(travel.token().task(),start.token().task());assertEquals(travel.command(),start.command());resumed=true;
			}
			if(resumed)break;
		}
		assertTrue(resumed);
	}
	@Test void prospectiveDimRegionLimitPreservesTheInterruptedReturnTask() {
		var result=kernel().advance(dimReturnBranch(),returnWorld(FEET,4),List.of(),10);
		assertTrue(result.state().outcome().isEmpty(),"an out-of-region destination must request recovery, not fail the mission");
		assertTrue(result.effects().isEmpty(),"the prohibited destination must not reach the motor");
		assertEquals(2,result.state().stack().getLast().id());
	}
	@Test void regainedLightStopsRetreatBeforeResumingTheSameReturnFrame() {
		var kernel=kernel();var initial=dimReturnBranch();
		var requested=kernel.advance(initial,returnWorld(FEET,4),List.of(),10);
		var saved=assertInstanceOf(RegainLight.class,requested.state().stack().getLast().task());
		assertEquals(initial.stack().getLast().task(),saved.saved().task());
		var moving=kernel.advance(requested.state(),returnWorld(FEET,4),List.of(),11);
		var retreat=(Start<VoxelCommand>)moving.effects().getFirst();
		assertEquals(FEET.offset(0,0,-4),((VoxelCommand.Navigate)retreat.command()).stance());
		var lit=returnWorld(FEET.offset(0,0,-2),15);
		var stop=kernel.advance(moving.state(),lit,List.of(),12);
		assertEquals(List.of(new Stop<>(retreat.token())),stop.effects());
		assertTrue(kernel.advance(stop.state(),lit,List.of(),13).effects().isEmpty());
		var resumed=kernel.advance(stop.state(),lit,List.of(new Released(retreat.token())),13);
		var work=(Start<VoxelCommand>)resumed.effects().getFirst();
		assertEquals(2,work.token().task());
		assertEquals(FEET.offset(0,0,16),((VoxelCommand.Navigate)work.command()).stance());
		assertTrue(resumed.events().stream().anyMatch(e->e.detail().equals("working_light_regained")));
	}
	@Test void staleRefugeLightDoesNotCountAsSuccessfulRecovery() {
		var kernel=kernel();var requested=kernel.advance(dimReturnBranch(),returnWorld(FEET,4),List.of(),10);
		var moving=kernel.advance(requested.state(),returnWorld(FEET,4),List.of(),11);
		var command=(Start<VoxelCommand>)moving.effects().getFirst();
		var atEnd=returnWorld(FEET.offset(0,0,-4),4);var known=new HashMap<>(atEnd.known());
		known.put(new Pos(0,2,-4),new Seen("air",true,true,false,4,12));
		atEnd=new StoneAcquisition.World(atEnd.eye(),atEnd.feet(),atEnd.inventory(),known);
		var failed=kernel.advance(moving.state(),atEnd,List.of(new Finished(command.token(),Outcome.success("arrived"))),12);
		assertEquals("lighting_refuge_still_dim",failed.state().outcome().orElseThrow().evidence());
		assertTrue(failed.effects().isEmpty());
	}
	@Test void aStalledLightRecoveryReleasesBeforeItsDeadlineFailure() {
		var kernel=kernel();var requested=kernel.advance(dimReturnBranch(),returnWorld(FEET,4),List.of(),10);
		var recovery=(RegainLight)requested.state().stack().getLast().task();
		var moving=kernel.advance(requested.state(),returnWorld(FEET,4),List.of(),11);
		var command=(Start<VoxelCommand>)moving.effects().getFirst();
		var stop=kernel.advance(moving.state(),returnWorld(FEET,4),List.of(),recovery.deadline());
		assertEquals(List.of(new Stop<>(command.token())),stop.effects());
		var failed=kernel.advance(stop.state(),returnWorld(FEET,4),List.of(new Released(command.token())),recovery.deadline()+1);
		assertEquals("lighting_refuge_budget_exhausted",failed.state().outcome().orElseThrow().evidence());
		assertTrue(failed.effects().isEmpty());
	}
	private static State<Task> dimReturnBranch() {
		var prior=new SearchPrior("ore",0,16,20,List.of("stone"));
		var route=new ArrayList<Pos>();for(int z=0;z<=16;z++)route.add(FEET.offset(0,0,z));
		var search=new UndergroundSearch.Task(prior,List.of("ore_block"),FEET,FEET,0,0,Map.of(),Set.of(),Optional.empty(),Optional.empty(),null,route);
		var task=new ResumeExplore(new Explore(search,Map.of(),Set.of()),ReturnNavigation.State.begin(route),new ToolRepair("pick",Set.of()));
		var lightRoute=new ArrayList<Pos>();for(int z=-4;z<=0;z++)lightRoute.add(FEET.offset(0,0,z));
		var policy=new LightingPolicy.State(true,Set.of(LightingPolicy.Repair.PLACEMENT,LightingPolicy.Repair.SUPPLY),Optional.of(LightingPolicy.Allowance.begin(FEET,90,new SurvivalPolicy.Vitals(0,20,false,false))));
		return branch(task,new Ready<>(),new WorkingLight(policy,lightRoute));
	}
	private static StoneAcquisition.World returnWorld(Pos feet,int light) {
		var known=new HashMap<Pos,Seen>();
		for(int z=-4;z<=16;z++)for(int y=0;y<=2;y++)known.put(new Pos(0,y,z),new Seen(y==0?"stone":"air",y>0,true,y==0,z==-4?15:light,10));
		return new StoneAcquisition.World(new Pose(.5,2.62,feet.z()+.5,0,0),feet,Map.of("pick",1),known);
	}
	@Test void darknessInterruptsGatheringEvenWithoutAnExploreTask() {
		var kernel = kernel();
		var first = kernel.advance(kernel.begin("s","r",new Mission("log",1,0,0),0),world(15,Map.of("minecraft:torch",2)),List.of(),1);
		var mining = assertInstanceOf(Start.class,first.effects().getFirst());
		assertInstanceOf(VoxelCommand.Break.class,mining.command());
		var dark = kernel.advance(first.state(),world(4,Map.of("minecraft:torch",2)),List.of(),2);
		assertEquals(List.of(new Stop<>(mining.token())),dark.effects());
		var repair=kernel.advance(dark.state(),world(4,Map.of("minecraft:torch",2)),List.of(new Released(mining.token())),3);
		var place=assertInstanceOf(VoxelCommand.Place.class,((Start<?>)repair.effects().getFirst()).command());
		assertNotEquals(((VoxelCommand.Break)mining.command()).target(),place.support(),"the resumed harvest must not destroy its new light source");
	}
	@Test void LightingDoesNotForgetTheSupplyRouteWhenPassingAnExistingTorch() {
		var kernel=kernel();var first=kernel.advance(kernel.begin("s","r",new Mission("log",1,0,0),0),world(15,Map.of()),List.of(),1);
		var moved=world(15,Map.of()); var feet=FEET.offset(1,0,0);
		moved=new StoneAcquisition.World(new Pose(1.5,2.62,.5,0,30),feet,moved.inventory(),moved.known());
		var next=kernel.advance(first.state(),moved,List.of(),2);
		assertEquals(List.of(FEET,feet),((Mission)next.state().stack().getFirst().task()).light().route());
	}
	@Test void supplyAndPlacementResumeTheSameHarvestFrameWithoutRejectingItsTarget() {
		var kernel=kernel(); var stock=new HashMap<>(Map.of("coal",2,"stick",2));
		var first=kernel.advance(kernel.begin("s","r",new Mission("log",1,0,0),0),world(15,stock),List.of(),1);
		var original=(Start<VoxelCommand>)first.effects().getFirst();
		var state=first.state(); List<Feedback> feedback=List.of(); int light=4, crafts=0, placements=0; boolean resumed=false;
		for (int tick=2;tick<30;tick++) {
			var step=kernel.advance(state,world(light,stock),feedback,tick); state=step.state(); feedback=List.of();
			for (var effect:step.effects()) {
				if (effect instanceof Stop<VoxelCommand> stop) feedback=List.of(new Released(stop.token()));
				if (effect instanceof Start<VoxelCommand> start) {
					if (start.command() instanceof VoxelCommand.Craft) { crafts++; stock.merge("minecraft:torch",4,Integer::sum); }
					else if (start.command() instanceof VoxelCommand.Place) { placements++; light=10; stock.merge("minecraft:torch",-1,Integer::sum); }
					else if (start.command() instanceof VoxelCommand.Break) {
						assertEquals(original.command(),start.command()); assertEquals(original.token().task(),start.token().task()); resumed=true;
					}
					else fail("Unexpected repair command: "+start.command());
					feedback=List.of(new Finished(start.token(),Outcome.success("observed")));
				}
			}
			if (resumed) break;
		}
		assertTrue(resumed); assertEquals(2,crafts); assertEquals(1,placements);
	}
	@Test void oneRestorationAllowanceSpansHarvestingAndCraftingAndExpiresDuringAWait() {
		var kernel=kernel();
		var first=kernel.advance(kernel.begin("s","r",new Mission("minecraft:torch",4,0,0),0),world(4,Map.of("stick",1)),List.of(),1);
		var initial=((Mission)first.state().stack().getFirst().task()).light().policy().allowance().orElseThrow();
		assertEquals(LightingPolicy.Purpose.LIGHT_RESTORATION,initial.purpose());
		var harvest=(Start<VoxelCommand>)first.effects().getFirst(); assertInstanceOf(VoxelCommand.Break.class,harvest.command());
		var settled=kernel.advance(first.state(),world(4,Map.of("stick",1,"coal",1)),List.of(new Finished(harvest.token(),Outcome.success("harvested"))),20);
		assertTrue(settled.effects().isEmpty());
		assertEquals(initial,((Mission)settled.state().stack().getFirst().task()).light().policy().allowance().orElseThrow());
		var crafting=kernel.advance(settled.state(),world(4,Map.of("stick",1,"coal",1)),List.of(),21);
		var craft=(Start<VoxelCommand>)crafting.effects().getFirst(); assertInstanceOf(VoxelCommand.Craft.class,craft.command());
		assertEquals(initial,((Mission)crafting.state().stack().getFirst().task()).light().policy().allowance().orElseThrow());
		var transaction=kernel.advance(crafting.state(),world(4,Map.of()),List.of(),initial.expiresAt());
		assertTrue(transaction.effects().isEmpty(),"ordinary light expiry lets the inventory transaction drain");
		var expired=kernel.advance(transaction.state(),world(4,Map.of()),List.of(new Finished(craft.token(),Outcome.failure("transaction_failed"))),initial.expiresAt()+1);
		assertEquals(ResultKind.FAILED,expired.state().outcome().orElseThrow().kind());
		assertTrue(expired.effects().isEmpty(),"expired permission cannot restart another prerequisite");
	}
	@Test void passiveFurnaceWaitCanBeInterruptedAndItsOriginalDeadlineSurvives() {
		var recipe=new Smelt("smelt","raw","bar",1,"furnace",200);
		var waiting=new CollectBatch(recipe,new Pos(0,1,2),0,400,0);
		var initial=branch(waiting,new Sleeping<>(200),new WorkingLight(LightingPolicy.State.begin(),List.of(FEET)));
		var kernel=kernel(); var repair=kernel.advance(initial,world(4,Map.of("minecraft:torch",2)),List.of(),50);
		var placement=(Start<VoxelCommand>)repair.effects().getFirst(); assertInstanceOf(VoxelCommand.Place.class,placement.command());
		assertTrue(repair.effects().stream().noneMatch(e -> e instanceof Stop));
		var saved=(ResumeWork)repair.state().stack().get(1).task(); assertEquals(waiting,saved.saved().task());
		var state=repair.state(); List<Feedback> feedback=List.of(new Finished(placement.token(),Outcome.success("placed")));
		for (int tick=51;tick<56;tick++) { var step=kernel.advance(state,world(10,Map.of("minecraft:torch",1)),feedback,tick);state=step.state(); feedback=List.of();assertTrue(step.effects().isEmpty()); }
		assertEquals(waiting,state.stack().getLast().task()); assertEquals(200,((Sleeping<?>)state.stack().getLast().phase()).wakeTick());
	}
	@Test void healthLossRevokesTheSharedAllowanceBeforeTimeExpiryAndLateFeedbackCannotResumeWork() {
		var kernel=kernel(); var dim=world(4,Map.of("stick",1));
		var first=kernel.advance(kernel.begin("s","r",new Mission("minecraft:torch",4,0,0),0),dim,List.of(),1);
		var mining=(Start<VoxelCommand>)first.effects().getFirst();
		var injured=new StoneAcquisition.World(dim.eye(),dim.feet(),dim.inventory(),dim.known(),dim.footholds(),new SurvivalPolicy.Vitals(0,18,false,false));
		var stop=kernel.advance(first.state(),injured,List.of(),2); assertEquals(List.of(new Stop<>(mining.token())),stop.effects());
		var done=kernel.advance(stop.state(),injured,List.of(new Released(mining.token())),3);
		assertEquals(ResultKind.FAILED,done.state().outcome().orElseThrow().kind());
		assertTrue(done.state().outcome().orElseThrow().evidence().contains("health_loss"));
		assertTrue(kernel.advance(done.state(),dim,List.of(new Finished(mining.token(),Outcome.success("late"))),4).effects().isEmpty());
	}
	private static State<Task> branch(Task task,Phase<Task> phase,WorkingLight light) {
		return new State<>("s","r",List.of(new Frame<>(1,new Mission("goal",1,0,0,light),new WaitingChild<>(2,"work"),Optional.empty(),Optional.empty()),
			new Frame<>(2,task,phase,Optional.empty(),Optional.empty())),3,1,5000,0,Optional.empty());
	}
	@Test void missingInputsRetraceTheRememberedExitAndRepairReturnsToTheInterruptedSite() {
		var entrance=FEET.offset(0,0,-2); var gathering=new Gather(BOOK.harvesting().getFirst(),1,FEET,0,Set.of(),Set.of(),null);
		var initial=branch(gathering,new Ready<>(),new WorkingLight(LightingPolicy.State.begin(),List.of(entrance,FEET)));
		var kernel=kernel(); var outbound=kernel.advance(initial,world(4,Map.of()),List.of(),1);
		var travel=(Start<VoxelCommand>)outbound.effects().getFirst();
		assertEquals(entrance,assertInstanceOf(VoxelCommand.Navigate.class,travel.command()).stance());
		var repair=(ResumeWork)outbound.state().stack().get(1).task(); assertEquals(gathering,repair.saved().task());
		assertEquals(List.of(entrance,FEET),repair.returning().route());
		var resumed=new ResumeWork(repair.saved(),repair.returning(),repair.repair(),Optional.of(Outcome.success("supplied")),repair.deadline(),repair.supplyOrigin(),repair.baseline());
		var atExit=world(10,Map.of("minecraft:torch",8));
		atExit=new StoneAcquisition.World(new Pose(.5,2.62,-1.5,0,30),entrance,atExit.inventory(),atExit.known());
		var returned=kernel.advance(branch(resumed,new Ready<>(),initial.stack().getFirst().task() instanceof Mission m ? m.light():null),atExit,List.of(),5);
		assertInstanceOf(ReturnWork.class,returned.state().stack().getLast().task());
		returned=kernel.advance(returned.state(),atExit,List.of(),6);
		assertEquals(FEET,assertInstanceOf(VoxelCommand.Navigate.class,((Start<?>)returned.effects().getFirst()).command()).stance());
	}
	@Test void repairDeadlineCancelsAStalledDependencyWithoutGrantingAnotherMotorOwner() {
		var initial=branch(new CollectBatch(new Smelt("s","raw","bar",1,"furnace",200),new Pos(0,1,2),0,400,0),new Sleeping<>(200),WorkingLight.begin());
		var kernel=kernel(); var repair=kernel.advance(initial,world(4,Map.of("minecraft:torch",2)),List.of(),1);
		var placing=(Start<VoxelCommand>)repair.effects().getFirst(); var parent=(ResumeWork)repair.state().stack().get(1).task();
		var expired=kernel.advance(repair.state(),world(4,Map.of("minecraft:torch",2)),List.of(),parent.deadline());
		assertEquals(List.of(new Stop<>(placing.token())),expired.effects());
		assertEquals(repair.state().nextAttempt(),expired.state().nextAttempt());
		assertTrue(kernel.advance(expired.state(),world(4,Map.of("minecraft:torch",2)),List.of(),parent.deadline()+1).effects().isEmpty());
	}
	private static StoneAcquisition.World world(int light,Map<String,Integer> stock) {
		var known = new HashMap<Pos,Seen>();
		for (int x=-3;x<=3;x++) for (int z=-3;z<=3;z++) for (int y=0;y<=3;y++) {
			var p = new Pos(x,y,z); known.put(p,new Seen(y==0 ? "minecraft:stone":"minecraft:air",y!=0,true,y==0,light,1));
		}
		known.put(new Pos(0,1,2),new Seen("log_block",false,true,true,light,1));
		return new StoneAcquisition.World(new Pose(.5,2.62,.5,0,30),FEET,stock,known);
	}
}
