package io.github.jdiscordbots.mee.bypasser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.internal.JDAImpl;

public class MsgListener extends ListenerAdapter{
	
	private static final Logger LOG=LoggerFactory.getLogger(MsgListener.class);
	
	public MsgListener() {
		if(new File("roles.dat").exists()) {
			try {
				load();
			} catch (ClassNotFoundException | IOException e) {
				LOG.error("Cannot load roles", e);
			}
		}
	}
	
	private Map<String, Map<Integer,String>> roles=new HashMap<>();
	private boolean hasModPerm(Member member) {
		return member.hasPermission(Permission.MANAGE_ROLES);
	}
	@Override
	public void onGuildMessageReceived(GuildMessageReceivedEvent event) {
		String msgContent=event.getMessage().getContentRaw();
		Guild g=event.getGuild();
		if(msgContent.startsWith("!rank")) {
			try {
				Member member;
				if(event.getMessage().getMentionedMembers().isEmpty()) {
					member=event.getMember();
				}else {
					member=event.getMessage().getMentionedMembers().get(0);
				}
				int level = MeeAPI.getLevel(g.getId(), member.getId());
				Map<Integer,String> gRoles=roles.get(event.getGuild().getId());
				Member selfMember=g.getSelfMember();
				if(gRoles!=null) {
					gRoles.forEach((k,v)->{
						Role role=g.getRoleById(v);
						if(k<=level&&!member.getRoles().contains(role)) {
							if(selfMember.hasPermission(Permission.MANAGE_ROLES)&&selfMember.canInteract(role)) {
								g.addRoleToMember(member, role).queue();
							}else if(event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
								event.getChannel().sendMessage("Cannot assign role "+role).getName()+" as "+selfMember.getEffectiveName()+" does not have the necessary permissions.").queue();
							}
						}
					});
				}
			} catch (JSONException e) {
				if(LOG.isErrorEnabled()) {
					LOG.error("Cannot load leveling data from the Mee API in guild {}", g.getName(),e);
				}
			}catch(IOException e) {
				if(e.getMessage()!=null&&e.getMessage().startsWith("Server returned HTTP response code: 401 for URL: ")) {
					if(event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
						event.getChannel().sendMessage("The server leaderboard needs to be public for automatic role assigning to work. This can be configured on https://mee6.xyz/dashboard/"+event.getGuild().getId()+"/leaderboard").queue();
					}
					if(LOG.isInfoEnabled()) {
						LOG.info("Server leaderboard is not public for guild {}", event.getGuild().getName());
					}
				}else {
					if(LOG.isErrorEnabled()) {
						LOG.error("Cannot load leveling data from the Mee API in guild {}", event.getGuild().getName(),e);
					}
				}
			}
		}else if(event.getMessage().getContentRaw().startsWith("mb!")) {
			if(!hasModPerm(event.getMember())) {
				return;
			}
			String[] split=event.getMessage().getContentRaw().split(" ");
			String[] args=Arrays.copyOfRange(split, 1, split.length);
			String command=split[0].substring("mb!".length()).toLowerCase();
			switch(command){
				case "add":{
					if(args.length<2) {
						event.getChannel().sendMessage("Error - missing args").queue();
						return;
					}
					int level;
					try{
						level = Integer.parseInt(args[0]);
					}catch(NumberFormatException e) {
						event.getChannel().sendMessage("Error - level required").queue();
						return;
					}
					Role role=null;
					try {
						role=event.getGuild().getRoleById(args[1]);
					} catch (NumberFormatException e) {
						//let it stay null
					}
					if(role==null) {
						event.getChannel().sendMessage("Error - Role not found").queue();
						return;
					}
					Map<Integer,String> gRoles;
					if(roles.containsKey(event.getGuild().getId())) {
						gRoles=roles.get(event.getGuild().getId());
					} else {
						gRoles=new HashMap<>();
						roles.put(event.getGuild().getId(), gRoles);
					}
					gRoles.put(level, role.getId());
					try {
						save();
					} catch (IOException e) {
						LOG.error("Cannot save data",e);
					}
					event.getChannel().sendMessage("added Role "+role.getName()+" for level "+level).queue();
					break;
				}
				case "remove":{
					int level;
					try{
						level = Integer.parseInt(args[0]);
					}catch(NumberFormatException e) {
						event.getChannel().sendMessage("Error - level required").queue();
						return;
					}
					String role=null;
					if(roles.containsKey(event.getGuild().getId())) {
						Map<Integer,String> gRoles=roles.get(event.getGuild().getId());
						role=gRoles.remove(level);
						if(gRoles.isEmpty()) {
							roles.remove(event.getGuild().getId());
						}
					}
					if(role!=null) {
						event.getChannel().sendMessage("removed role "+event.getGuild().getRoleById(role).getAsMention()+" from level "+level).allowedMentions(Arrays.asList()).queue();
					}else {
						event.getChannel().sendMessage("no role for level "+level).allowedMentions(Arrays.asList()).queue();
					}
					try {
						save();
					} catch (IOException e) {
						LOG.error("Cannot save data",e);
					}
					break;
				}
				case "show":{
					if(roles.containsKey(event.getGuild().getId())) {
						event.getChannel().sendMessage(""+roles.get(event.getGuild().getId()).entrySet().stream().map(e->"Level "+e.getKey()+" is assigned to role "+event.getGuild().getRoleById(e.getValue()).getAsMention()).collect(Collectors.joining("\n"))).allowedMentions(Arrays.asList()).queue();
					}else {
						event.getChannel().sendMessage("no roles").queue();
					}
					break;
				}
				case "id":{
					if(args.length<1) {
						event.getChannel().sendMessage("missing args").queue();
						return;
					}
					String name=String.join(" ",args);
					String title="Roles of name `"+name+"`";
					String desc=event.getGuild().getRolesByName(name, true).stream().map(role->role.getAsMention()+" ("+role.getId()+")").collect(Collectors.joining("\n"));
					if(event.getGuild().getSelfMember().hasPermission(event.getChannel(),Permission.MESSAGE_EMBED_LINKS)) {
						EmbedBuilder eb=new EmbedBuilder();
						eb.setTitle(title);
						eb.setDescription(desc);
						event.getChannel().sendMessage(eb.build()).queue();
					}else {
						event.getChannel().sendMessage("**"+title+"**\n"+desc).queue();
					}
					
					break;
				}
				case "list":{
					Map<Integer, String> gRoles = roles.get(event.getGuild().getId());
					if(gRoles==null||gRoles.isEmpty()) {
						event.getChannel().sendMessage("Mee-bypasser is not set up for this guild!").queue();
					}else {
						event.getChannel().sendMessage(gRoles.entrySet().stream().map(entry->"Level: "+entry.getKey()+", Role: "+event.getJDA().getRoleById(entry.getValue()).getAsMention()).collect(Collectors.joining("\n"))).allowedMentions(Arrays.asList()).queue();
					}
					break;
				}
				default:{
					event.getChannel().sendMessage("Command not found: "+command).queue();
				}
			}
		}
	}
	private void save() throws IOException {
		try(ObjectOutputStream oos=new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream("roles.dat")))){
			oos.writeObject(roles);
		}
	}
	@SuppressWarnings("unchecked")
	private void load() throws IOException, ClassNotFoundException {
		try(ObjectInputStream ois=new ObjectInputStream(new BufferedInputStream(new FileInputStream("roles.dat")))){
			roles=(Map<String, Map<Integer, String>>) ois.readObject();
		}
	}
}
