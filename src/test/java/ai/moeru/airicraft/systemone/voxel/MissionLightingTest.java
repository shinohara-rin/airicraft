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
	@Test void darknessInterruptsGatheringEvenWithoutAnExploreTask() {
		var kernel = kernel();
		var first = kernel.advance(kernel.begin("s","r",new Mission("log",1,0,0),0),world(15,Map.of("minecraft:torch",2)),List.of(),1);
		var mining = assertInstanceOf(Start.class,first.effects().getFirst());
		assertInstanceOf(VoxelCommand.Break.class,mining.command());
		var dark = kernel.advance(first.state(),world(4,Map.of("minecraft:torch",2)),List.of(),2);
		assertEquals(List.of(new Stop<>(mining.token())),dark.effects());
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
		var crafting=kernel.advance(first.state(),world(4,Map.of("stick",1,"coal",1)),List.of(new Finished(harvest.token(),Outcome.success("harvested"))),20);
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
